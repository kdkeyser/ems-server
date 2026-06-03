# ---- Stage 1: build the fat jar ----
FROM docker.io/library/eclipse-temurin:21-jdk AS build
WORKDIR /app

# Warm the Gradle wrapper/deps cache on dependency files first for better layer caching.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon --version

# Now the sources.
COPY src ./src
RUN ./gradlew --no-daemon shadowJar

# ---- Stage 2: runtime ----
FROM docker.io/library/eclipse-temurin:21-jre AS runtime
WORKDIR /app

# Run as a non-root user. Pre-create + own /data and /config so that a freshly-created
# (empty) named volume mounted at /data inherits ems ownership and SQLite can write ems.db.
RUN useradd --system --uid 10001 --create-home ems \
    && mkdir -p /data /config \
    && chown -R ems:ems /data /config
USER ems

COPY --from=build /app/build/libs/ems-server-all.jar /app/ems-server.jar

# Config is mounted at /config/config.yaml; SQLite DB lives under /data.
ENV EMS_CONFIG=/config/config.yaml
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/ems-server.jar"]
