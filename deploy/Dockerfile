# syntax=docker/dockerfile:1.7
# Builds both pgcp images from the repository root:
#   docker build -f deploy/Dockerfile --target control-plane -t pgcp-control-plane .
#   docker build -f deploy/Dockerfile --target agent -t pgcp-agent .

FROM node:24-bookworm-slim AS web
WORKDIR /src/web
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY web/ ./
RUN npm run build

FROM eclipse-temurin:21-jdk-noble AS jvm
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN --mount=type=cache,target=/root/.gradle ./gradlew --version --no-daemon
COPY agent-api agent-api
COPY agent agent
COPY control-plane control-plane
COPY --from=web /src/web/dist web/dist
RUN --mount=type=cache,target=/root/.gradle ./gradlew :agent:bootJar :control-plane:bootJar -x test --no-daemon

FROM eclipse-temurin:21-jre-noble AS control-plane
RUN useradd --system --uid 10001 --home-dir /var/lib/pgcp-cp pgcp && install -d -o pgcp -m 0700 /var/lib/pgcp-cp
COPY --from=jvm /src/control-plane/build/libs/pgcp-control-plane.jar /opt/pgcp/control-plane.jar
USER pgcp
ENV JAVA_TOOL_OPTIONS="-XX:+UseSerialGC -Xmx256m -XX:MaxMetaspaceSize=160m -Xss512k -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=4 \
    CMD curl -fsS http://127.0.0.1:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/opt/pgcp/control-plane.jar"]

FROM docker:29-cli AS dockercli

# The agent is the only component with Docker access. It runs as root because it owns the Docker socket and
# the instance directories; its API exposes no generic command execution.
FROM eclipse-temurin:21-jre-noble AS agent
COPY --from=dockercli /usr/local/bin/docker /usr/local/bin/docker
COPY --from=dockercli /usr/local/libexec/docker/cli-plugins/docker-compose /usr/local/libexec/docker/cli-plugins/docker-compose
COPY --from=dockercli /usr/local/libexec/docker/cli-plugins/docker-buildx /usr/local/libexec/docker/cli-plugins/docker-buildx
COPY images/postgres /opt/pgcp/images/postgres
COPY --from=jvm /src/agent/build/libs/pgcp-agent.jar /opt/pgcp/agent.jar
ENV JAVA_TOOL_OPTIONS="-XX:+UseSerialGC -Xmx160m -XX:MaxMetaspaceSize=128m -Xss512k -XX:+ExitOnOutOfMemoryError"
EXPOSE 8443
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=4 \
    CMD curl -fsS http://127.0.0.1:8081/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/opt/pgcp/agent.jar"]
