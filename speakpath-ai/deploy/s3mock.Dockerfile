ARG IMAGE_REGISTRY=public.ecr.aws/docker/library
FROM ${IMAGE_REGISTRY}/eclipse-temurin:21-jre-jammy
WORKDIR /app
ADD https://repo.maven.apache.org/maven2/com/adobe/testing/s3mock/4.12.4/s3mock-4.12.4-exec.jar /app/s3mock.jar
EXPOSE 9090
ENTRYPOINT ["java", "-jar", "/app/s3mock.jar"]
