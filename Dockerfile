FROM eclipse-temurin:21.0.13_9-jdk@sha256:07f6f9094fd87619b35630a68782fb34db71d29a8c0337c8bf7c7d93755b88a5 AS builder
WORKDIR /workspace
COPY backend/ ./
# CI validates tests separately; runtime packaging produces both application artifacts once.
RUN ./gradlew --no-daemon --max-workers=2 :api-service:bootJar :worker-service:bootJar

FROM eclipse-temurin:21.0.13_9-jre@sha256:194729a50fcca3b498180858b54cf1b2e3816208cf8d8a1fca9856635f72ca8a
ARG APP_MODULE=api-service
RUN case "$APP_MODULE" in api-service|worker-service) ;; *) exit 1 ;; esac \
    && groupadd --gid 10001 skulker \
    && useradd --uid 10001 --gid 10001 --no-create-home --home-dir /app --shell /usr/sbin/nologin skulker
WORKDIR /app
COPY --from=builder --chown=10001:10001 /workspace/${APP_MODULE}/build/libs/application.jar /app/application.jar
ENV SERVER_ADDRESS=0.0.0.0 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65.0 -XX:+ExitOnOutOfMemoryError"
USER 10001:10001
EXPOSE 8080 8081
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
