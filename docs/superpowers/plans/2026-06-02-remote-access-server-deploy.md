# Remote Access (Server + Deployment) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Containerize the `ems-server` and load its config from a mounted file (so device IPs/credentials/DB path change without rebuilding), then publish a NetBird/DNS runbook that exposes only `/ws` + `/status-ws` to the public internet at `https://ec29.ems.konektis.io` behind edge Header Authentication and auto-TLS.

**Architecture:** A multi-stage Docker image builds the existing shadowJar and runs it on a slim JRE. `docker-compose.yml` runs the server (host network, so it reaches LAN Modbus/HTTP devices and is reachable by the NetBird agent on `:8080`) alongside a NetBird agent container that makes the NAS a WireGuard peer. The NetBird Cloud Reverse Proxy (HTTP/L7 service) terminates TLS, validates a static Bearer API-key header, and path-routes `/ws` + `/status-ws` to the NAS. No `/status-ws` or `status.html` code changes — `/status-ws` stays app-unauthenticated and is gated remotely by the edge header.

**Tech Stack:** Kotlin 2.2.20 / Ktor 3.3.1, Hoplite 2.9.0 (`hoplite-core` + `hoplite-yaml`), Gradle 9.1 shadowJar, Docker / docker-compose, NetBird Cloud Reverse Proxy.

**Spec:** `docs/superpowers/specs/2026-06-02-remote-access-netbird-design.md`

---

## File Structure

- **Modify** `src/main/kotlin/config/Config.kt` — `loadConfig` gains an optional file-path parameter; loads from a file when present, falls back to the classpath resource otherwise.
- **Modify** `src/test/kotlin/config/ConfigTest.kt` — add tests for file-load and resource-fallback.
- **Create** `Dockerfile` — multi-stage build (JDK build → JRE runtime).
- **Create** `.dockerignore` — keep the build context small and avoid leaking local artifacts.
- **Create** `docker-compose.yml` — `ems-server` + `netbird` services.
- **Create** `deploy/config.yaml.template` — production config template (DB at `/data/ems.db`, placeholder strong creds, OCPP charger).
- **Create** `docs/remote-access-netbird-runbook.md` — manual NetBird/DNS setup + validation steps.

Note: `loadConfig` is also called by `src/main/kotlin/Application.kt:44` and `src/main/kotlin/tools/BatteryWatchdogTest.kt:24`. Both call `loadConfig("/config.yaml")` positionally; the new parameter is optional with a default, so neither call site needs to change.

---

## Task 1: Externalize config loading (file source with resource fallback)

**Files:**
- Modify: `src/main/kotlin/config/Config.kt:1-5` (imports) and `:96-101` (`loadConfig`)
- Test: `src/test/kotlin/config/ConfigTest.kt`

- [ ] **Step 1: Write the failing tests**

Add these two tests to `src/test/kotlin/config/ConfigTest.kt`. They exercise the new optional `filePath` parameter: one loads from a real temp file, the other forces the fallback to the classpath resource by pointing at a path that does not exist.

```kotlin
package io.konektis.config

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ConfigTest {
    @Test
    fun testLoadConfig() {
        val config = loadConfig("/config.yaml", filePath = null)
        assertNotNull(config)
        assertEquals(2, config.devices.solar?.size)
        assertEquals(1, config.devices.heatPump?.size)
        assertEquals(1, config.devices.charger?.size)
    }

    @Test
    fun testDatabaseDefaults() {
        val config = loadConfig("/config.yaml", filePath = null)
        assertEquals("ems.db", config.database.path)
        assertEquals(30, config.ocpp.callTimeoutSeconds)
    }

    @Test
    fun testLoadConfigFromFile() {
        val yaml = """
            grid:
              type: P1HomeWizard
              gridType: Phase3_400V
              host: 10.9.9.9
            devices:
              charger:
                - type: OCPP
                  name: From File Charger
                  chargePointId: FILE01
                  chargingCurrent: { min: 6.0, max: 32.0 }
            ocpp:
              enabled: true
              heartbeatInterval: 300
              connectionTimeout: 60
            websocket:
              username: fileuser
              password: filepass
            database:
              path: /data/ems.db
        """.trimIndent()
        val tmp = File.createTempFile("ems-config", ".yaml")
        tmp.writeText(yaml)
        tmp.deleteOnExit()

        val config = loadConfig("/config.yaml", filePath = tmp.absolutePath)

        assertEquals("From File Charger", config.devices.charger.first().name)
        assertEquals("/data/ems.db", config.database.path)
        assertEquals("fileuser", config.websocket.username)
    }

    @Test
    fun testFallsBackToResourceWhenFileAbsent() {
        val config = loadConfig("/config.yaml", filePath = "/no/such/ems-config-does-not-exist.yaml")
        // Falls back to the bundled classpath resource (the dev config).
        assertEquals(2, config.devices.solar?.size)
        assertEquals("ems.db", config.database.path)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'io.konektis.config.ConfigTest'`
Expected: FAIL — `testLoadConfigFromFile` and `testFallsBackToResourceWhenFileAbsent` won't compile because `loadConfig` has no `filePath` parameter (`too many arguments` / `no value passed for parameter`).

- [ ] **Step 3: Implement the file-aware `loadConfig`**

In `src/main/kotlin/config/Config.kt`, add the imports and replace `loadConfig`.

Change the imports block at the top (`:1-5`) to add `addFileSource` and `java.io.File`:

```kotlin
package io.konektis.config

import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.addFileSource
import com.sksamuel.hoplite.addResourceSource
import kotlinx.serialization.Serializable
import java.io.File
```

Replace the `loadConfig` function (`:96-101`) with:

```kotlin
/**
 * Loads config from an external file when one exists, otherwise from the bundled classpath resource.
 *
 * In a container the file lives at a mounted path (default `/config/config.yaml`, overridable with
 * the `EMS_CONFIG` env var) so device IPs/credentials/DB path can change without rebuilding the image.
 * Local runs and tests have no such file, so they fall back to the classpath `resource`.
 *
 * @param resource classpath resource to fall back to (e.g. "/config.yaml")
 * @param filePath external file to prefer; defaults to $EMS_CONFIG or "/config/config.yaml".
 *        Pass `null` to skip the file entirely (used by tests to force the resource path).
 */
fun loadConfig(
    resource: String,
    filePath: String? = System.getenv("EMS_CONFIG") ?: "/config/config.yaml",
): Config {
    val file = filePath?.let { File(it) }
    val builder = ConfigLoaderBuilder.default()
    val source = if (file != null && file.exists()) {
        builder.addFileSource(file)
    } else {
        builder.addResourceSource(resource)
    }
    return source.build().loadConfigOrThrow<Config>()
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests 'io.konektis.config.ConfigTest'`
Expected: PASS (4 tests). The full suite should also stay green: `./gradlew test` → all existing tests pass (the `Application.kt` and `BatteryWatchdogTest.kt` call sites are unchanged because `filePath` is optional).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/config/Config.kt src/test/kotlin/config/ConfigTest.kt
git commit -m "feat(config): load config from external file with classpath fallback

Prefers \$EMS_CONFIG (default /config/config.yaml) when the file exists, so a
containerized deploy can mount config.yaml without rebuilding; falls back to the
bundled classpath resource for local runs and tests."
```

---

## Task 2: Dockerfile (multi-stage build → JRE runtime)

**Files:**
- Create: `Dockerfile`
- Create: `.dockerignore`

- [ ] **Step 1: Create `.dockerignore`**

Keeps the build context small and avoids copying local build output, IDE state, the runtime SQLite DB, and the Android subproject into the image build.

```
.git
.gradle
build/
android/
*.db
.idea
.vscode
.claude/
.superpowers/
docs/
local.properties
*.iml
```

- [ ] **Step 2: Create the `Dockerfile`**

Stage 1 builds the fat jar with the project's own Gradle wrapper (Gradle 9.1 runs on JDK 21). Stage 2 runs it on a slim JRE. The jar name comes from `rootProject.name` (`ems-server`) + `version` (`0.0.1`) + the shadow classifier → `ems-server-0.0.1-all.jar`.

```dockerfile
# ---- Stage 1: build the fat jar ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Warm the Gradle wrapper/deps cache on dependency files first for better layer caching.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon --version

# Now the sources.
COPY src ./src
RUN ./gradlew --no-daemon shadowJar

# ---- Stage 2: runtime ----
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# Run as a non-root user.
RUN useradd --system --uid 10001 --create-home ems
USER ems

COPY --from=build /app/build/libs/ems-server-0.0.1-all.jar /app/ems-server.jar

# Config is mounted at /config/config.yaml; SQLite DB lives under /data.
ENV EMS_CONFIG=/config/config.yaml
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/ems-server.jar"]
```

- [ ] **Step 3: Build the image to verify it compiles and assembles**

Run: `docker build -t ems-server:dev .`
Expected: build succeeds; final line `naming to docker.io/library/ems-server:dev` (or `Successfully tagged ems-server:dev`). The shadowJar step runs the full build incl. tests; all tests pass.

- [ ] **Step 4: Smoke-test the container with the bundled config (no mount)**

With no `/config/config.yaml` present, `loadConfig` falls back to the classpath resource, so the server still boots (it will try to reach the dev config's device IPs and log connection failures — that's expected; we only check it starts and serves HTTP).

```bash
docker run --rm -d --name ems-smoke -p 8080:8080 ems-server:dev
sleep 8
curl -fsS -o /dev/null -w '%{http_code}\n' http://localhost:8080/status   # expect 200
docker logs ems-smoke | tail -n 20
docker stop ems-smoke
```

Expected: `curl` prints `200` (the `status.html` page is served without auth), and the logs show the server started on port 8080.

- [ ] **Step 5: Commit**

```bash
git add Dockerfile .dockerignore
git commit -m "build(docker): multi-stage Dockerfile + .dockerignore for ems-server

Stage 1 builds the shadowJar (eclipse-temurin:21-jdk via the Gradle wrapper);
stage 2 runs ems-server-0.0.1-all.jar on a 21-jre as a non-root user. Reads
config from \$EMS_CONFIG (/config/config.yaml) when mounted, else the bundled resource."
```

---

## Task 3: Production config template

**Files:**
- Create: `deploy/config.yaml.template`

- [ ] **Step 1: Create `deploy/config.yaml.template`**

This is the file the NAS operator copies to `deploy/config.yaml` (gitignored via the existing `*.yaml`? — no, only `*.db` is ignored, so name it `.template` and have the operator copy it). It points the SQLite DB at the `/data` volume and ships placeholder **strong** credentials that the operator must replace before exposing the instance. Device IPs mirror the LAN layout; the operator edits them to match their hardware.

```yaml
# Production config for the containerized EMS server.
# Copy to deploy/config.yaml, fill in real device IPs and STRONG websocket credentials,
# then mount it read-only at /config/config.yaml (see docker-compose.yml).
#
# SECURITY: the public internet path (NetBird RP) requires the edge API-key header AND
# these websocket credentials on /ws. Do NOT ship the dev defaults (user/password).

grid:
  type: P1HomeWizard
  gridType: Phase3_400V
  host: 192.168.129.10        # <-- HomeWizard P1 meter IP

devices:
  solar:
    - type: SMA_Sunny_Boy
      name: Solar East
      host: 192.168.129.21    # <-- inverter IP
    - type: SMA_Sunny_Boy
      name: Solar West
      host: 192.168.129.22    # <-- inverter IP
  heatPump:
    - type: DaikinHomeHub
      name: Heat Pump
      host: 192.168.129.30    # <-- Daikin HomeHub IP
  charger:
    - type: OCPP
      name: Garage Charger
      chargePointId: CP01     # MUST match the id the charger dials in its OCPP URL
      chargingCurrent: { min: 6.0, max: 32.0 }

ocpp:
  enabled: true
  heartbeatInterval: 300
  connectionTimeout: 60
  callTimeoutSeconds: 30
  acceptUnknownChargePoints: true
  acceptUnknownIdTags: true
  autoProbeOnBoot: true

# REPLACE these before exposing the instance. Must match the app's username/password.
websocket:
  username: CHANGE_ME_strong_user
  password: CHANGE_ME_long_random_password

database:
  path: /data/ems.db          # persisted on the Docker volume, not in the image
```

- [ ] **Step 2: Verify the template parses as valid config**

The template uses placeholder hosts, so don't boot it against hardware — just confirm Hoplite accepts the shape using the loader added in Task 1:

```bash
cat > /tmp/verify-template.kts <<'EOF'
// not executed; structural check only
EOF
# Structural check: the file is valid YAML and has the required top-level keys.
docker run --rm -v "$PWD/deploy/config.yaml.template:/config/config.yaml:ro" \
  -e EMS_CONFIG=/config/config.yaml ems-server:dev \
  java -jar /app/ems-server.jar &
SMOKE_PID=$!
sleep 8
curl -fsS -o /dev/null -w '%{http_code}\n' http://localhost:8080/status 2>/dev/null || true
kill $SMOKE_PID 2>/dev/null || true
```

Expected: the server starts and parses the mounted template (it logs device connection failures against the placeholder IPs — that's fine; a config *parse* error would instead crash at startup with a Hoplite decoding error). If it crashes before "started", the template shape is wrong — fix it.

> Note: if `docker run ... java -jar` doesn't expose the port without `-p`, run with `--network host` on Linux or add `-p 8080:8080`. The point of this step is only to confirm the mounted template is parsed, which you can also see in the logs.

- [ ] **Step 3: Commit**

```bash
git add deploy/config.yaml.template
git commit -m "deploy: production config template (DB on /data, strong-cred placeholders)"
```

---

## Task 4: docker-compose (ems-server + NetBird agent)

**Files:**
- Create: `docker-compose.yml`

- [ ] **Step 1: Create `docker-compose.yml`**

Both services use the **host network**: `ems-server` must reach LAN devices on `192.168.129.x` (Modbus/HTTP) and accept the charger's inbound OCPP connection, and the NetBird agent must reach the server at `127.0.0.1:8080` to tunnel RP traffic to it. The server's config is mounted read-only; `ems.db` lives on a named volume. The NetBird agent enrolls with a setup key from the environment and persists its state.

```yaml
services:
  ems-server:
    build: .
    image: ems-server:latest
    container_name: ems-server
    restart: unless-stopped
    network_mode: host          # reach LAN Modbus/HTTP devices + accept charger OCPP on :8080
    environment:
      EMS_CONFIG: /config/config.yaml
    volumes:
      - ./deploy/config.yaml:/config/config.yaml:ro
      - ems-data:/data

  netbird:
    image: netbirdio/netbird:latest
    container_name: netbird
    restart: unless-stopped
    network_mode: host          # WireGuard peer; reaches ems-server at 127.0.0.1:8080
    cap_add:
      - NET_ADMIN
    devices:
      - /dev/net/tun:/dev/net/tun
    environment:
      # Provide a reusable setup key from the NetBird dashboard (see the runbook).
      NB_SETUP_KEY: ${NB_SETUP_KEY:?set NB_SETUP_KEY in .env or the environment}
      NB_HOSTNAME: ems-nas
    volumes:
      - netbird-config:/etc/netbird

volumes:
  ems-data:
  netbird-config:
```

- [ ] **Step 2: Validate the compose file**

Run: `docker compose config`
Expected: prints the resolved configuration with no errors. If it complains that `NB_SETUP_KEY` is unset, that's the `:?` guard working — set a dummy value to validate: `NB_SETUP_KEY=dummy docker compose config` should print clean YAML.

- [ ] **Step 3: Commit**

```bash
git add docker-compose.yml
git commit -m "deploy(compose): ems-server + netbird agent (host network, mounted config, data volume)"
```

---

## Task 5: NetBird / DNS runbook

**Files:**
- Create: `docs/remote-access-netbird-runbook.md`

- [ ] **Step 1: Write the runbook**

This is the manual operator procedure that lives outside code. It must cover: prerequisites, deploying the two containers on the NAS, enrolling the NetBird peer, creating the RP HTTP service with header auth + path routes, the custom-domain CNAME, and end-to-end validation (incl. the WebSocket-over-L7 risk check and the L4 fallback).

```markdown
# Remote Access Runbook — NetBird Reverse Proxy

Exposes the EMS app's two WebSocket endpoints (`/ws`, `/status-ws`) at
`https://ec29.ems.konektis.io` over TLS, without a fixed IP or open ports. The charger
endpoint (`/ocpp/{id}`) and the local admin pages (`/ocpp-ui`, `/status`) stay LAN-only.

See the design spec: `docs/superpowers/specs/2026-06-02-remote-access-netbird-design.md`.

## Prerequisites

- TerraMaster F4-423 NAS on the home LAN (`192.168.129.0/24`) with Docker + docker-compose.
- A NetBird Cloud account (dashboard at https://app.netbird.io).
- DNS control for `konektis.io`.
- This repo checked out on the NAS.

## 1. Prepare the config

```bash
cp deploy/config.yaml.template deploy/config.yaml
# Edit deploy/config.yaml: set real device IPs and a STRONG websocket username/password.
# Record those credentials — the app's Settings must use the same username/password.
```

## 2. Create a NetBird setup key

In the NetBird dashboard → **Setup Keys** → create a **reusable** key. Copy it.

```bash
echo "NB_SETUP_KEY=<paste-setup-key>" > .env   # docker-compose reads this
```

## 3. Start the containers

```bash
docker compose up -d --build
docker compose ps           # ems-server + netbird both "running"
docker compose logs -f ems-server   # confirm "Responding at ... 8080" and device polling
```

The `netbird` container enrolls the NAS as a peer. Confirm it appears under
**Peers** in the dashboard (hostname `ems-nas`) with status connected.

## 4. Create the Reverse Proxy service (HTTP / L7)

In the dashboard → **Network / Reverse Proxy** (beta) → **Add service**:

- **Protocol:** HTTP (L7).
- **Custom domain:** `ec29.ems.konektis.io`.
- **Target:** peer `ems-nas`, port `8080`, protocol HTTP.
- **Path routes:** add **only** `/ws` and `/status-ws`. Do NOT expose `/`, `/ocpp`,
  `/ocpp-ui`, `/status`, or `/users` — they must stay LAN-only.

## 5. Enable Header Authentication (edge auth)

On the service, enable **Header Authentication**:

- Header: `Authorization`, scheme **Bearer**, with a long random API key.
- Save the key — it goes into the app's Settings ("API key").

Requests without this header are rejected at the NetBird proxy cluster and never reach
the NAS. (Optionally also add a country/IP allow-list for defense in depth.)

## 6. Point the custom domain at NetBird

NetBird shows a CNAME target for the custom domain. At the `konektis.io` DNS host, add:

```
ec29.ems   CNAME   <netbird-proxy-cluster-hostname>
```

Wait until the RP service status reaches **active** (TLS issued via Let's Encrypt).
If status is `certificate_failed`, recheck the CNAME and that `ec29.ems.konektis.io`
resolves to the NetBird target.

## 7. Validate end-to-end

Install `websocat` (a generic WebSocket client) to test independently of the app.

**Edge auth — must be rejected without the header:**
```bash
websocat "wss://ec29.ems.konektis.io/ws"
# expect an HTTP error (401/403) from the NetBird edge, no upgrade
```

**`/status-ws` with the header — must stream StatusState (no app-layer auth):**
```bash
websocat -H "Authorization: Bearer <api-key>" "wss://ec29.ems.konektis.io/status-ws"
# expect JSON StatusState frames
```

**`/ws` with header but wrong app creds — server closes after Authenticate:**
```bash
websocat -H "Authorization: Bearer <api-key>" "wss://ec29.ems.konektis.io/ws"
# then type: {"type":"Authenticate","username":"wrong","password":"wrong"}
# expect an Unauthorized message / socket close
```

**`/ws` with header + correct creds — authenticates and streams updates:**
```bash
websocat -H "Authorization: Bearer <api-key>" "wss://ec29.ems.konektis.io/ws"
# type: {"type":"Authenticate","username":"<cfg user>","password":"<cfg pass>"}
# expect Authenticated + PowerUsageUpdate/ModeUpdate frames
```

**LAN unchanged:** `http://<NAS-LAN-IP>:8080/status` still serves `status.html` with no
credentials; the charger still connects to `ws://<NAS-LAN-IP>:8080/ocpp/CP01`.

**Not exposed:** `https://ec29.ems.konektis.io/ocpp-ui` must NOT load (no path route).

## Risk: WebSocket over the L7 HTTP service

The primary risk is whether the RP HTTP/L7 service forwards the WebSocket `Upgrade`
handshake (header auth runs on that first HTTP request, so the upgrade must survive it).
Validate with the `websocat` checks above **before** wiring the app.

**Fallback if L7 does not proxy WebSockets:** switch the RP service to **L4 TLS
passthrough** and terminate TLS on the NAS with a small reverse proxy (Caddy) holding a
DNS-01 Let's Encrypt cert for `ec29.ems.konektis.io`. That path loses edge header auth,
so `/status-ws` would then need app-layer auth added on the server (and matching
`status.html` / app changes). Prefer L7; only fall back if validation fails.

## Operations

- **Rotate the API key:** update it on the RP service and in the app's Settings.
- **Rotate websocket creds:** edit `deploy/config.yaml`, `docker compose restart ems-server`,
  update the app's username/password.
- **Logs:** `docker compose logs -f ems-server` / `docker compose logs -f netbird`.
- **Update the server:** `git pull && docker compose up -d --build`.
```

- [ ] **Step 2: Commit**

```bash
git add docs/remote-access-netbird-runbook.md
git commit -m "docs(remote-access): NetBird RP + DNS runbook (deploy, header auth, path routes, validation)"
```

---

## Self-Review

**Spec coverage:**
- A1 Dockerfile → Task 2. A2 docker-compose (ems-server + netbird) → Task 4. A3 externalize config loading (file + resource fallback, `EMS_CONFIG`, default `/config/config.yaml`) → Task 1. A4 strong credentials documented → Task 3 (template placeholders) + Task 5 (runbook step 1/5). C11–C14 NetBird/DNS runbook (peer enroll, RP HTTP service header auth + path routes `/ws`+`/status-ws`, custom-domain CNAME, validation) → Task 5. "No `/status-ws`/`status.html` change" → honored (no task touches them; runbook validation asserts `/status` still works without auth). Testing — `loadConfig` file/fallback unit test → Task 1; build smoke (`docker build` + container serves `/ws`+`/status-ws`) → Task 2 + runbook §7; manual/E2E `websocat` matrix → runbook §7. WebSocket-over-L7 risk + L4 fallback → runbook "Risk" section. Out-of-scope items (no remote `/ocpp-ui`, no OIDC, no HA) are respected — only `/ws`+`/status-ws` are path-routed.

**Placeholder scan:** No TBD/TODO. The only intentional `CHANGE_ME_*` / `<paste...>` tokens are operator-supplied secrets in the config template and runbook, which is correct.

**Type consistency:** `loadConfig(resource, filePath)` signature is identical across Task 1 implementation and all four tests; existing positional call sites (`Application.kt`, `BatteryWatchdogTest.kt`) keep working via the optional default. Jar name `ems-server-0.0.1-all.jar` matches `rootProject.name` + `version` + shadow classifier. `EMS_CONFIG=/config/config.yaml` is consistent across Dockerfile, compose, and `loadConfig`'s default.
