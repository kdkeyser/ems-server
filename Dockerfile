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

COPY --from=build /app/build/libs/ems-server-all.jar /app/ems-server.jar

# Config is mounted at /config/config.yaml; SQLite DB lives under /data.
ENV EMS_CONFIG=/config/config.yaml
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/ems-server.jar"]
