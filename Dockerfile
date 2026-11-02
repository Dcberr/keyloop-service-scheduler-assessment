# Build the executable JAR with ./mvnw clean verify before building this runtime image.
FROM eclipse-temurin:21-jre-jammy@sha256:1eac8b8fee11ca7d6725a48bc5d41992ed1561504ebae25490d2d062847e8269
WORKDIR /opt/scheduler
COPY --chown=10001:10001 target/service-scheduler-0.0.1-SNAPSHOT.jar app.jar
COPY --chmod=0555 scripts/docker/healthcheck.sh /opt/scheduler/healthcheck.sh
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=5s --timeout=4s --start-period=30s --retries=10 CMD ["/bin/bash", "/opt/scheduler/healthcheck.sh"]
ENTRYPOINT ["java", "-jar", "/opt/scheduler/app.jar"]
