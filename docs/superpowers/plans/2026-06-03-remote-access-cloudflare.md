# Remote Access via Cloudflare Tunnel — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the (WebSocket-incapable) NetBird L7 reverse proxy with a Cloudflare Tunnel + Cloudflare Access service token, so the phone app reaches the EMS server at `wss://ec29.ems.konektis.io`.

**Architecture:** `cloudflared` runs beside `ems-server` on the Compose bridge, dials out to the Cloudflare edge (no open ports), and forwards `/ws` + `/status-ws` to `http://ems-server:8080`. Cloudflare terminates TLS, proxies the WebSocket upgrade, and enforces an Access service token. The server is unchanged; the app sends `CF-Access-Client-Id`/`CF-Access-Client-Secret` instead of a Bearer key.

**Tech Stack:** Kotlin/Ktor server (unchanged), Android (Compose, DataStore, Ktor client), Docker Compose, Podman build, Cloudflare Tunnel + Access.

**Spec:** `docs/superpowers/specs/2026-06-03-remote-access-cloudflare-design.md`

---

## File map

| File | Change |
|------|--------|
| `android/.../data/settings/SettingsRepository.kt` | Replace `apiKey` with `cfAccessClientId` + `cfAccessClientSecret` |
| `android/.../test/.../SettingsRepositoryTest.kt` | Update defaults + round-trip assertions |
| `android/.../data/ws/EdgeAuth.kt` | **New** pure helper: `edgeAuthHeaders(Settings)` |
| `android/.../test/.../EdgeAuthTest.kt` | **New** unit tests for the helper |
| `android/.../data/ws/ControlWsClient.kt` | Send CF headers via the helper (drop Bearer) |
| `android/.../data/ws/StatusWsClient.kt` | Same |
| `android/.../ui/settings/SettingsScreen.kt` | Two CF fields instead of the API-key field |
| `docker-compose.yml` | Remove `netbird` + alias; add `cloudflared` |
| `deploy/build-podman.sh` | Bundle `cloudflared` instead of `netbird` |
| `docs/remote-access-cloudflare-runbook.md` | **New** |
| `docs/remote-access-getting-started.md` | Rewrite for Cloudflare |
| `docs/remote-access-netbird-runbook.md`, `deploy/netbird-whoami-test/` | **Delete** |

Android source root: `android/app/src/main/kotlin/io/konektis/ems`, tests: `android/app/src/test/kotlin/io/konektis/ems`. Run app tests from `android/` with `./gradlew testDebugUnitTest`.

---

## Task 1: Settings model — swap API key for CF service-token fields

**Files:**
- Modify: `android/app/src/main/kotlin/io/konektis/ems/data/settings/SettingsRepository.kt`
- Test: `android/app/src/test/kotlin/io/konektis/ems/SettingsRepositoryTest.kt`

- [ ] **Step 1: Update the failing test first**

Replace the `apiKey` assertions. In `SettingsRepositoryTest.kt`, change the defaults test's last line and the round-trip test:

```kotlin
    @Test
    fun `defaults match backend dev config`() = testScope.runTest {
        val s = repo.settingsFlow.first()
        assertEquals(SettingsRepository.DEFAULT_SERVER_URL, s.serverUrl)
        assertEquals(SettingsRepository.DEFAULT_USERNAME, s.username)
        assertEquals(SettingsRepository.DEFAULT_PASSWORD, s.password)
        assertFalse(s.useTls)                       // default off for LAN/dev
        assertEquals("", s.cfAccessClientId)        // no CF token by default
        assertEquals("", s.cfAccessClientSecret)
    }

    @Test
    fun `save and load round-trips`() = testScope.runTest {
        repo.save(
            Settings(
                serverUrl = "ec29.ems.konektis.io",
                username = "user",
                password = "pass",
                useTls = true,
                cfAccessClientId = "cf-id",
                cfAccessClientSecret = "cf-secret"
            )
        )
        val s = repo.settingsFlow.first()
        assertEquals("ec29.ems.konektis.io", s.serverUrl)
        assertEquals("user", s.username)
        assertEquals("pass", s.password)
        assertEquals(true, s.useTls)
        assertEquals("cf-id", s.cfAccessClientId)
        assertEquals("cf-secret", s.cfAccessClientSecret)
    }
```

(The third test `settingsFlow emits updated value after save` uses positional `Settings("host1:8080", "", "")` and stays unchanged — the new fields default.)

- [ ] **Step 2: Run the test, verify it fails to compile**

Run: `cd android && ./gradlew testDebugUnitTest --tests "io.konektis.ems.SettingsRepositoryTest"`
Expected: FAIL — `cfAccessClientId`/`cfAccessClientSecret` unresolved.

- [ ] **Step 3: Update `SettingsRepository.kt`**

Replace the `Settings` data class, the flow mapping, the `save` body, and the companion keys/defaults:

```kotlin
data class Settings(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val useTls: Boolean = false,
    val cfAccessClientId: String = "",
    val cfAccessClientSecret: String = ""
)
```

```kotlin
    val settingsFlow: Flow<Settings> = store.data.map { prefs ->
        Settings(
            serverUrl = prefs[KEY_SERVER_URL] ?: DEFAULT_SERVER_URL,
            username  = prefs[KEY_USERNAME]   ?: DEFAULT_USERNAME,
            password  = prefs[KEY_PASSWORD]   ?: DEFAULT_PASSWORD,
            useTls    = prefs[KEY_USE_TLS]    ?: DEFAULT_USE_TLS,
            cfAccessClientId     = prefs[KEY_CF_ID]     ?: DEFAULT_CF_ID,
            cfAccessClientSecret = prefs[KEY_CF_SECRET] ?: DEFAULT_CF_SECRET
        )
    }

    suspend fun save(settings: Settings) {
        store.edit { prefs ->
            prefs[KEY_SERVER_URL] = settings.serverUrl
            prefs[KEY_USERNAME]   = settings.username
            prefs[KEY_PASSWORD]   = settings.password
            prefs[KEY_USE_TLS]    = settings.useTls
            prefs[KEY_CF_ID]      = settings.cfAccessClientId
            prefs[KEY_CF_SECRET]  = settings.cfAccessClientSecret
        }
    }

    companion object {
        const val DEFAULT_SERVER_URL = "10.0.2.2:8080"
        const val DEFAULT_USERNAME   = "user"
        const val DEFAULT_PASSWORD   = "password"
        const val DEFAULT_USE_TLS    = false
        const val DEFAULT_CF_ID      = ""
        const val DEFAULT_CF_SECRET  = ""

        private val KEY_SERVER_URL = stringPreferencesKey("server_url")
        private val KEY_USERNAME   = stringPreferencesKey("username")
        private val KEY_PASSWORD   = stringPreferencesKey("password")
        private val KEY_USE_TLS    = booleanPreferencesKey("use_tls")
        private val KEY_CF_ID      = stringPreferencesKey("cf_access_client_id")
        private val KEY_CF_SECRET  = stringPreferencesKey("cf_access_client_secret")
    }
```

- [ ] **Step 4: Run the test, verify it passes**

Run: `cd android && ./gradlew testDebugUnitTest --tests "io.konektis.ems.SettingsRepositoryTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/io/konektis/ems/data/settings/SettingsRepository.kt \
        android/app/src/test/kotlin/io/konektis/ems/SettingsRepositoryTest.kt
git commit -m "feat(app): replace apiKey with Cloudflare Access service-token settings"
```

---

## Task 2: Edge-auth header helper + WS clients

**Files:**
- Create: `android/app/src/main/kotlin/io/konektis/ems/data/ws/EdgeAuth.kt`
- Test: `android/app/src/test/kotlin/io/konektis/ems/EdgeAuthTest.kt`
- Modify: `android/app/src/main/kotlin/io/konektis/ems/data/ws/ControlWsClient.kt`, `.../StatusWsClient.kt`

- [ ] **Step 1: Write the failing test**

Create `EdgeAuthTest.kt`:

```kotlin
package io.konektis.ems

import io.konektis.ems.data.settings.Settings
import io.konektis.ems.data.ws.edgeAuthHeaders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EdgeAuthTest {

    @Test
    fun `both values set yields both CF headers`() {
        val h = edgeAuthHeaders(Settings(cfAccessClientId = "id", cfAccessClientSecret = "sec"))
        assertEquals("id", h["CF-Access-Client-Id"])
        assertEquals("sec", h["CF-Access-Client-Secret"])
        assertEquals(2, h.size)
    }

    @Test
    fun `missing either value yields no headers`() {
        assertTrue(edgeAuthHeaders(Settings(cfAccessClientId = "id")).isEmpty())
        assertTrue(edgeAuthHeaders(Settings(cfAccessClientSecret = "sec")).isEmpty())
        assertTrue(edgeAuthHeaders(Settings()).isEmpty())
    }
}
```

- [ ] **Step 2: Run it, verify it fails**

Run: `cd android && ./gradlew testDebugUnitTest --tests "io.konektis.ems.EdgeAuthTest"`
Expected: FAIL — `edgeAuthHeaders` unresolved.

- [ ] **Step 3: Create `EdgeAuth.kt`**

```kotlin
package io.konektis.ems.data.ws

import io.konektis.ems.data.settings.Settings

/**
 * Cloudflare Access service-token headers for the edge-auth layer. Both values must be set;
 * otherwise no headers are sent (local LAN use bypasses Cloudflare and needs none).
 */
internal fun edgeAuthHeaders(s: Settings): Map<String, String> =
    if (s.cfAccessClientId.isNotBlank() && s.cfAccessClientSecret.isNotBlank()) {
        mapOf(
            "CF-Access-Client-Id" to s.cfAccessClientId,
            "CF-Access-Client-Secret" to s.cfAccessClientSecret
        )
    } else {
        emptyMap()
    }
```

- [ ] **Step 4: Run it, verify it passes**

Run: `cd android && ./gradlew testDebugUnitTest --tests "io.konektis.ems.EdgeAuthTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Wire the helper into `ControlWsClient.kt`**

Remove the import `import io.ktor.http.HttpHeaders` (no longer used). Replace the `request = { ... }` block:

```kotlin
                        client.webSocket(
                            urlString = wsUrl(s.serverUrl, s.useTls, "/ws"),
                            request = {
                                edgeAuthHeaders(s).forEach { (name, value) -> header(name, value) }
                            }
                        ) {
```

- [ ] **Step 6: Wire the helper into `StatusWsClient.kt`**

Remove the import `import io.ktor.http.HttpHeaders`. Replace the `request = { ... }` block:

```kotlin
                client.webSocket(
                    urlString = wsUrl(s.serverUrl, s.useTls, "/status-ws"),
                    request = {
                        edgeAuthHeaders(s).forEach { (name, value) -> header(name, value) }
                    }
                ) {
```

- [ ] **Step 7: Compile + full unit tests**

Run: `cd android && ./gradlew testDebugUnitTest`
Expected: PASS (all tests, including unchanged `WsUrlTest`).

- [ ] **Step 8: Commit**

```bash
git add android/app/src/main/kotlin/io/konektis/ems/data/ws/EdgeAuth.kt \
        android/app/src/test/kotlin/io/konektis/ems/EdgeAuthTest.kt \
        android/app/src/main/kotlin/io/konektis/ems/data/ws/ControlWsClient.kt \
        android/app/src/main/kotlin/io/konektis/ems/data/ws/StatusWsClient.kt
git commit -m "feat(app): send Cloudflare Access service-token headers on both WS clients"
```

---

## Task 3: Settings screen — two CF fields

**Files:**
- Modify: `android/app/src/main/kotlin/io/konektis/ems/ui/settings/SettingsScreen.kt`

- [ ] **Step 1: Replace the `apiKey` state with two CF fields**

Change the state line (around line 40):

```kotlin
    var cfId     by rememberSaveable(saved.cfAccessClientId)     { mutableStateOf(saved.cfAccessClientId) }
    var cfSecret by rememberSaveable(saved.cfAccessClientSecret) { mutableStateOf(saved.cfAccessClientSecret) }
```

- [ ] **Step 2: Replace the API-key `OutlinedTextField` with two fields**

Replace the single `apiKey` field block with:

```kotlin
            OutlinedTextField(
                value = cfId,
                onValueChange = { cfId = it },
                label = { Text("CF Access Client ID (remote)") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = cfSecret,
                onValueChange = { cfSecret = it },
                label = { Text("CF Access Client Secret (remote)") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
```

- [ ] **Step 3: Update the `save` call**

```kotlin
                    vm.save(
                        Settings(
                            serverUrl = serverUrl.trim(),
                            username = username.trim(),
                            password = password,
                            useTls = useTls,
                            cfAccessClientId = cfId.trim(),
                            cfAccessClientSecret = cfSecret.trim()
                        )
                    )
```

- [ ] **Step 4: Build the debug APK to confirm the UI compiles**

Run: `cd android && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL; APK at `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/io/konektis/ems/ui/settings/SettingsScreen.kt
git commit -m "feat(app): Settings UI for Cloudflare Access client ID + secret"
```

---

## Task 4: docker-compose — swap netbird for cloudflared

**Files:**
- Modify: `docker-compose.yml`

- [ ] **Step 1: Replace the whole file**

```yaml
# Runs the EMS server + a Cloudflare Tunnel agent on a shared bridge network.
#
# Images come from the tarball built by deploy/build-podman.sh — load it first:
#   docker load -i ems-images.tar.gz
#   TUNNEL_TOKEN=<cloudflare-tunnel-token> docker compose up -d
#
# cloudflared dials OUT to the Cloudflare edge (no inbound ports) and reaches the
# server over the shared bridge as http://ems-server:8080 (set that as the tunnel's
# public-hostname service). TLS, the WebSocket upgrade, path scoping (/ws + /status-ws
# only), and the Access service-token check are handled at the Cloudflare edge. See the runbook.
services:
  ems-server:
    image: localhost/ems-server:latest   # matches the tag in the Podman-built tarball
    pull_policy: never                   # loaded via `docker load`, never pulled
    container_name: ems-server
    restart: unless-stopped
    # Bridge network: outbound to LAN Modbus/HTTP devices (192.168.129.x) works via
    # the host. Publish 8080 so the charger's inbound OCPP and the LAN admin pages
    # (/status, /ocpp-ui) are reachable on the NAS's port 8080.
    ports:
      - "8080:8080"
    environment:
      EMS_CONFIG: /config/config.yaml
    volumes:
      - ./deploy/config.yaml:/config/config.yaml:ro
      - ems-data:/data

  cloudflared:
    image: cloudflare/cloudflared:latest
    container_name: cloudflared
    restart: unless-stopped
    command: tunnel --no-autoupdate run
    environment:
      # Token from the Cloudflare dashboard tunnel (see the runbook).
      TUNNEL_TOKEN: ${TUNNEL_TOKEN:?set TUNNEL_TOKEN in .env or the environment}

volumes:
  ems-data:
```

- [ ] **Step 2: Verify it parses and has the right shape**

Run:
```bash
python3 -c "import yaml; d=yaml.safe_load(open('docker-compose.yml')); s=d['services']; assert set(s)=={'ems-server','cloudflared'}, s.keys(); assert 'netbird' not in s; assert d['services']['cloudflared']['image']=='cloudflare/cloudflared:latest'; assert list(d['volumes'])==['ems-data']; print('compose OK')"
```
Expected: `compose OK`.

- [ ] **Step 3: Commit**

```bash
git add docker-compose.yml
git commit -m "feat(deploy): run cloudflared instead of netbird on the bridge"
```

---

## Task 5: build-podman.sh — bundle cloudflared

**Files:**
- Modify: `deploy/build-podman.sh`

- [ ] **Step 1: Replace the whole file**

```bash
#!/usr/bin/env bash
#
# Build the EMS deployment images with Podman and bundle them into a single
# tarball for transfer to the NAS.
#
# Produces ONE gzipped multi-image archive containing both containers:
#   - ems-server   (built from this repo's Dockerfile, tagged localhost/ems-server:latest)
#   - cloudflared  (Cloudflare Tunnel agent, pulled from Docker Hub)
#
# The archive is docker-archive format, so `docker load` on the NAS reads it directly.
# Then `docker compose up -d` runs both (see docker-compose.yml).
#
# Usage:
#   deploy/build-podman.sh                      # build + pull + bundle -> ems-images.tar.gz
#   deploy/build-podman.sh --tag v1.2.3         # tag the ems-server image
#   deploy/build-podman.sh --output /tmp/x.tar.gz   # write the bundle elsewhere
#
# Transfer + run on the NAS:
#   scp ems-images.tar.gz docker-compose.yml deploy/config.yaml .env admin@<NAS>:/Volume1/docker/ems/
#   ssh admin@<NAS>
#   cd /Volume1/docker/ems && docker load -i ems-images.tar.gz
#   TUNNEL_TOKEN=<token> docker compose up -d

set -euo pipefail

# --- defaults ---
# Podman namespaces local builds under localhost/. Keep that prefix so the image name in
# the archive matches docker-compose.yml after `docker load` on the NAS (a bare
# ems-server:latest would be normalized to docker.io/library/... and not match).
IMAGE="localhost/ems-server"
TAG="latest"
CLOUDFLARED_IMAGE="docker.io/cloudflare/cloudflared:latest"
OUTPUT=""   # resolved to <repo>/ems-images.tar.gz after we know the repo root

# --- parse args ---
while [[ $# -gt 0 ]]; do
    case "$1" in
        --tag)
            TAG="${2:?--tag needs a value}"
            shift 2
            ;;
        --output|-o)
            OUTPUT="${2:?--output needs a path}"
            shift 2
            ;;
        -h|--help)
            sed -n '2,26p' "$0"
            exit 0
            ;;
        *)
            echo "Unknown argument: $1" >&2
            echo "Run with --help for usage." >&2
            exit 2
            ;;
    esac
done

# --- locate repo root (this script lives in deploy/) ---
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
OUTPUT="${OUTPUT:-$REPO_ROOT/ems-images.tar.gz}"

if ! command -v podman >/dev/null 2>&1; then
    echo "ERROR: podman is not installed or not on PATH." >&2
    exit 1
fi

if [[ ! -f "$REPO_ROOT/Dockerfile" ]]; then
    echo "ERROR: Dockerfile not found at $REPO_ROOT/Dockerfile" >&2
    exit 1
fi

echo "==> Building $IMAGE:$TAG from $REPO_ROOT/Dockerfile"
podman build -t "$IMAGE:$TAG" -f "$REPO_ROOT/Dockerfile" "$REPO_ROOT"

echo "==> Pulling $CLOUDFLARED_IMAGE"
podman pull "$CLOUDFLARED_IMAGE"

echo "==> Bundling both images into $OUTPUT"
# -m / --multi-image-archive: put several images in one docker-archive (docker-loadable).
podman save -m "$IMAGE:$TAG" "$CLOUDFLARED_IMAGE" | gzip > "$OUTPUT"

echo "==> Done. Wrote $OUTPUT ($(du -h "$OUTPUT" | cut -f1)) containing:"
echo "      $IMAGE:$TAG"
echo "      $CLOUDFLARED_IMAGE"
echo
echo "    Load on the NAS with:  docker load -i $(basename "$OUTPUT")"
```

- [ ] **Step 2: Syntax check**

Run: `bash -n deploy/build-podman.sh && echo "syntax OK"`
Expected: `syntax OK`.

- [ ] **Step 3: Commit**

```bash
git add deploy/build-podman.sh
git commit -m "feat(deploy): bundle cloudflared (not netbird) into the image tarball"
```

---

## Task 6: New Cloudflare runbook

**Files:**
- Create: `docs/remote-access-cloudflare-runbook.md`

- [ ] **Step 1: Write the file**

```markdown
# Remote Access Runbook — Cloudflare Tunnel

Exposes the EMS app's two WebSocket endpoints (`/ws`, `/status-ws`) at
`https://ec29.ems.konektis.io` over TLS, without a fixed IP or open ports. The charger
endpoint (`/ocpp/{id}`) and the local admin pages (`/ocpp-ui`, `/status`, `/users`) stay LAN-only.

New to this? Start with the [Getting Started guide](remote-access-getting-started.md).
See the design spec: `docs/superpowers/specs/2026-06-03-remote-access-cloudflare-design.md`.

## Prerequisites

- TerraMaster NAS with Docker + docker-compose on the home LAN (`192.168.129.0/24`).
- A build machine with **Podman** and this repo (image built there, shipped as a tarball).
- A Cloudflare account; `konektis.io` managed by Cloudflare DNS (step 1).

## 1. Move konektis.io to Cloudflare

In the Cloudflare dashboard, add the `konektis.io` zone and switch the registrar's nameservers to
the pair Cloudflare assigns. Verify all existing records imported (MX/email, other subdomains).
Wait until the zone status is **Active**.

## 2. Prepare the config

\`\`\`bash
cp deploy/config.yaml.template deploy/config.yaml
# Edit: real device IPs + a STRONG websocket username/password (the app uses the same).
chmod 644 deploy/config.yaml   # the container's non-root user (uid 10001) reads it read-only
\`\`\`

## 3. Create the Tunnel (token-managed)

Dashboard → **Zero Trust → Networks → Tunnels → Create a tunnel → Cloudflared**. Name it
`ems-nas`. Copy the **tunnel token**. On the build machine:

\`\`\`bash
echo 'TUNNEL_TOKEN=<paste-tunnel-token>' > .env
\`\`\`

## 4. Build, ship, run

On the build machine (in the repo):

\`\`\`bash
deploy/build-podman.sh          # builds ems-server + pulls cloudflared -> ems-images.tar.gz
scp ems-images.tar.gz docker-compose.yml .env admin@<NAS>:/Volume1/docker/ems/
ssh admin@<NAS> 'mkdir -p /Volume1/docker/ems/deploy'
scp deploy/config.yaml admin@<NAS>:/Volume1/docker/ems/deploy/
\`\`\`

On the NAS:

\`\`\`bash
cd /Volume1/docker/ems
ss -tlnp | grep 8080        # must be EMPTY (TOS uses 8181/8443, so 8080 is normally free)
docker load -i ems-images.tar.gz
docker compose up -d
docker compose logs -f cloudflared   # expect "Registered tunnel connection"
\`\`\`

The tunnel shows **Healthy** in the dashboard.

## 5. Public hostname + path scoping

In the tunnel's **Public Hostname** config, add (in order):

1. Hostname `ec29.ems.konektis.io`, Path `ws`, Service `http://ems-server:8080`
2. Hostname `ec29.ems.konektis.io`, Path `status-ws`, Service `http://ems-server:8080`
3. A final **catch-all** rule → Service **`http_status:404`**

Only `/ws` and `/status-ws` reach the server; every other path returns 404, so the admin pages and
charger endpoint are never exposed.

## 6. Access service token (edge auth)

**Zero Trust → Access → Service Auth → Create Service Token**; copy the **Client ID** and
**Client Secret** (shown once).

**Zero Trust → Access → Applications → Add an application → Self-hosted**:

- Application domain: `ec29.ems.konektis.io`, path `ws`. Add a second application for path
  `status-ws` (or one app whose path matches both).
- Policy: action **Service Auth**, include the **service token** created above.

Requests without valid `CF-Access-Client-Id`/`CF-Access-Client-Secret` headers are rejected at the
edge. Save the Client ID + Secret for the app's Settings.

## 7. Validate end-to-end

\`\`\`bash
# with the service token -> expect 101 upgrade + StatusState JSON frames
websocat -H "CF-Access-Client-Id: <id>" -H "CF-Access-Client-Secret: <secret>" \
  "wss://ec29.ems.konektis.io/status-ws"

# without it -> rejected by Access, no upgrade
websocat "wss://ec29.ems.konektis.io/status-ws"

# /ws with token + correct creds: after the Authenticate frame, expect Authenticated + updates
websocat -H "CF-Access-Client-Id: <id>" -H "CF-Access-Client-Secret: <secret>" \
  "wss://ec29.ems.konektis.io/ws"
# then type: {"type":"Authenticate","username":"<cfg user>","password":"<cfg pass>"}

# not exposed
curl -i https://ec29.ems.konektis.io/ocpp-ui   # expect 404
\`\`\`

**LAN unchanged:** `http://<NAS-LAN-IP>:8080/status` still serves `status.html` with no creds;
the charger still connects to `ws://<NAS-LAN-IP>:8080/ocpp/CP01`.

## 8. Configure the app

App **Settings**: server `ec29.ems.konektis.io`, **Use TLS on**, **CF Access Client ID** + **CF
Access Client Secret** from step 6, username/password = the `websocket` creds from
`deploy/config.yaml`.

## Operations

- **Rotate the service token:** create a new one in Access, update the app, delete the old token.
- **Rotate websocket creds:** edit `deploy/config.yaml`, `docker compose restart ems-server`,
  update the app.
- **Update the server:** rebuild on the build machine (`deploy/build-podman.sh`), copy the new
  `ems-images.tar.gz` to the NAS, then `docker load -i ems-images.tar.gz && docker compose up -d`.
- **Logs:** `docker compose logs -f cloudflared` / `docker compose logs -f ems-server`.
```

- [ ] **Step 2: Commit**

```bash
git add docs/remote-access-cloudflare-runbook.md
git commit -m "docs(remote-access): Cloudflare Tunnel runbook"
```

---

## Task 7: Rewrite the Getting Started guide

**Files:**
- Modify: `docs/remote-access-getting-started.md`

- [ ] **Step 1: Replace the whole file**

```markdown
# Getting Started — Remote Access for the EMS App

This guide is the on-ramp for exposing the EMS server to the phone app over the internet,
securely, **without a fixed IP or any open ports** at home. It orients you and links to the
detailed [runbook](remote-access-cloudflare-runbook.md) for each step.

The server runs on a TerraMaster NAS in two containers (the EMS server + a Cloudflare Tunnel
agent, `cloudflared`). Cloudflare gives you a TLS URL `https://ec29.ems.konektis.io` and tunnels
it over an outbound-only connection to the NAS — nothing is exposed on your router.

> **Why Cloudflare and not NetBird?** NetBird's L7 reverse proxy cannot proxy WebSocket upgrades
> ([#5329](https://github.com/netbirdio/netbird/issues/5329)); a handshake returned `200` instead
> of `101`. Cloudflare Tunnel proxies WebSockets natively. See the design spec.

## How it fits together

\`\`\`
  Phone app            Cloudflare edge                NAS (home LAN 192.168.129.0/24)
 (anywhere)  ──wss──▶  TLS + Access service token ──▶ ┌──────── docker bridge ─────────┐
  + CF-Access-*        (proxies WebSockets)  tunnel   │ cloudflared ──▶ ems-server:8080 │
                                             (outbound)│                  │ (EMS server) │
                                                       └──────────────────┼─────────────┘
   LAN clients ──────────────────────────────────────────────────────────┤ :8080 published
   (charger OCPP, http://NAS:8080/status, /ocpp-ui)                       │ to the NAS
   solar / battery / heat pump / P1 meter  ◀── Modbus/HTTP (outbound) ─────┘
\`\`\`

- **Remote traffic:** `wss://ec29.ems.konektis.io` → Cloudflare edge (terminates TLS, checks the
  Access service token) → tunnel → `cloudflared` → `ems-server:8080`.
- **LAN traffic** (charger, admin pages): straight to the NAS's published port 8080, unchanged.
- **Devices:** the EMS server reaches them outbound from the bridge via the NAS.

## What you need

- A TerraMaster NAS with Docker + docker-compose, on the home LAN.
- A build machine with **Podman** and this repo (the NAS gets a tarball).
- A Cloudflare account, with `konektis.io` moved to Cloudflare DNS.

## The path, end to end

Each step links to the full runbook. Do them in order.

| # | Step | Runbook |
|---|------|---------|
| 1 | **Move DNS** — add `konektis.io` to Cloudflare; switch nameservers. | [§1](remote-access-cloudflare-runbook.md#1-move-konektisio-to-cloudflare) |
| 2 | **Config + tunnel** — fill `deploy/config.yaml`; create a token-managed tunnel; `TUNNEL_TOKEN` → `.env`. | [§2](remote-access-cloudflare-runbook.md#2-prepare-the-config), [§3](remote-access-cloudflare-runbook.md#3-create-the-tunnel-token-managed) |
| 3 | **Build + deploy** — `deploy/build-podman.sh`, `scp`, `docker load`, `docker compose up -d`. | [§4](remote-access-cloudflare-runbook.md#4-build-ship-run) |
| 4 | **Publish + scope** — public hostname routes only `/ws` + `/status-ws`; catch-all 404. | [§5](remote-access-cloudflare-runbook.md#5-public-hostname--path-scoping) |
| 5 | **Edge auth** — Access self-hosted app + Service Auth policy + service token. | [§6](remote-access-cloudflare-runbook.md#6-access-service-token-edge-auth) |
| 6 | **Validate** — `websocat` with the CF headers → expect `101` + frames. | [§7](remote-access-cloudflare-runbook.md#7-validate-end-to-end) |
| 7 | **Configure the app** — server host, TLS on, CF Client ID/Secret, creds. | [§8](remote-access-cloudflare-runbook.md#8-configure-the-app) |

## App configuration

In the app's **Settings**:

| Field | Remote (over the internet) | Local LAN / emulator |
|-------|----------------------------|----------------------|
| Server address | `ec29.ems.konektis.io` | `<NAS-LAN-IP>:8080` (emulator: `10.0.2.2:8080`) |
| Use TLS (wss) | **On** | Off |
| CF Access Client ID | from the Access service token | leave blank |
| CF Access Client Secret | from the Access service token | leave blank |
| Username / Password | the `websocket` creds from `deploy/config.yaml` | same |

The CF values are sent as `CF-Access-Client-Id` / `CF-Access-Client-Secret` on both WebSocket
endpoints (edge auth). The username/password go in the app-layer `Authenticate` frame on `/ws` only.

## Security model

- **No open ports / no fixed IP** — `cloudflared` only makes outbound connections.
- **Two auth layers:** Cloudflare Access rejects anything without a valid service token before it
  reaches the NAS; `/ws` additionally requires the app-layer username/password.
- **Least exposure:** only `/ws` + `/status-ws` are routed; every other path returns 404.
  `/ocpp/{id}`, `/ocpp-ui`, `/status`, `/users` stay LAN-only.
- **`/status-ws`** is app-unauthenticated so the local `status.html` works without creds; remotely
  it's gated by the Access service token.
- Replace the template's `CHANGE_ME_*` websocket creds before exposing anything.

## Where things live

| Path | Purpose |
|------|---------|
| `deploy/build-podman.sh` | Build both images → one `ems-images.tar.gz`. |
| `docker-compose.yml` | Runs ems-server + cloudflared on a shared bridge. |
| `deploy/config.yaml.template` | Production config template (copy → `deploy/config.yaml`). |
| `docs/remote-access-cloudflare-runbook.md` | Step-by-step deployment + validation. |

## Troubleshooting

| Symptom | Likely cause |
|---------|--------------|
| `docker compose up` fails to publish 8080 | Port 8080 already in use — `ss -tlnp \| grep 8080` should be empty. |
| Server logs can't read config | `deploy/config.yaml` not world-readable — `chmod 644`. |
| Tunnel not **Healthy** | Bad `TUNNEL_TOKEN`, or `cloudflared` can't reach the edge — `docker compose logs cloudflared`. |
| `wss://` returns 403 / login redirect | Missing/invalid CF Access service token headers. |
| `/ws` connects then closes | Wrong app username/password. |
| Remote path returns 404 | Public-hostname path scoping doesn't include the path, or hits the catch-all. |
```

- [ ] **Step 2: Commit**

```bash
git add docs/remote-access-getting-started.md
git commit -m "docs(remote-access): rewrite getting-started for Cloudflare Tunnel"
```

---

## Task 8: Remove dead NetBird artifacts + update memory

**Files:**
- Delete: `docs/remote-access-netbird-runbook.md`, `deploy/netbird-whoami-test/`
- Modify: memory note (see step 2)

- [ ] **Step 1: Delete and verify no references remain**

```bash
git rm docs/remote-access-netbird-runbook.md
git rm -r deploy/netbird-whoami-test
grep -rn "netbird\|ems-server.lan\|NB_SETUP_KEY" docs/ deploy/ docker-compose.yml || echo "(no stray references)"
```
Expected: only the historical spec `docs/superpowers/specs/2026-06-02-remote-access-netbird-design.md` (and the new cloudflare spec's "supersedes" line) may mention netbird; no operational doc/compose/script should.

- [ ] **Step 2: Update memory**

In `/home/koen/.claude/projects/-home-koen-Code-ems-server/memory/remote-access-netbird-plan.md`, update the description and add a line at the top of the body noting the pivot: NetBird L7 can't proxy WebSockets (#5329; handshake returned 200 not 101), so remote access moved to **Cloudflare Tunnel + Access service token**; see `docs/superpowers/specs/2026-06-03-remote-access-cloudflare-design.md` and `docs/remote-access-cloudflare-runbook.md`. Update the `MEMORY.md` pointer line text accordingly.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "chore(remote-access): remove dead NetBird runbook + whoami harness"
```

---

## Task 9: Final verification

- [ ] **Step 1: App tests + APK**

Run: `cd android && ./gradlew testDebugUnitTest assembleDebug`
Expected: all unit tests PASS; `app-debug.apk` produced.

- [ ] **Step 2: Server tests unaffected**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL (no server source changed).

- [ ] **Step 3: Compose + script sanity**

Run:
```bash
python3 -c "import yaml; d=yaml.safe_load(open('docker-compose.yml')); assert set(d['services'])=={'ems-server','cloudflared'}; print('compose OK')"
bash -n deploy/build-podman.sh && echo "script OK"
```
Expected: `compose OK` / `script OK`.

- [ ] **Step 4: Deferred (on the NAS / with Cloudflare)** — the dashboard steps (tunnel, public-hostname path scoping, Access service token) and the `websocat` end-to-end validation in runbook §7. Not scriptable here.

---

## Notes for the executor

- The `EMS_CONFIG`/server behavior is unchanged — do **not** modify any `src/main/kotlin` server code.
- `edgeAuthHeaders` is `internal`; unit tests in the same module can call it (same pattern as `wsUrl`/`WsUrlTest`).
- Cloudflare WebSockets must be enabled on the zone (default on) — note it if validation fails.
```
