# Build stage: Java 21 + Maven
ARG IMAGE_REGISTRY=public.ecr.aws/docker/library
FROM ${IMAGE_REGISTRY}/maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Copy dependency descriptors first to improve layer caching.
COPY pom.xml ./
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -DskipTests package \
    && mkdir -p /workspace/app \
    && cp /workspace/target/*.jar /workspace/app/app.jar

# Runtime stage: small JRE image, non-root user.
FROM ${IMAGE_REGISTRY}/eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system spring \
    && useradd --system --gid spring --home-dir /app spring

COPY --from=build --chown=spring:spring /workspace/app/app.jar /app/app.jar

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -Dfile.encoding=UTF-8"
EXPOSE 8080

USER spring:spring
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
