# syntax=docker/dockerfile:1
# Base images are pinned by tag AND digest (ADR 0041). Bump both together; a scheduled workflow (base-images.yml)
# reports when a newer digest exists for the tag.

FROM eclipse-temurin:21-jdk-alpine@sha256:0bfc69a4758a86710e5c474032d28400a8bd00874766f9e8b1642ac2fd293159 AS build
WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B dependency:go-offline

COPY src ./src
RUN ./mvnw -B -DskipTests package
RUN java -Djarmode=tools -jar target/*.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:21-jre-alpine@sha256:51ab5e3302e7141ce665ca3ea85e8b5cd648eafbc3c0c90dd79d6537684e4555
RUN apk add --no-cache curl \
 && addgroup -S -g 10001 app && adduser -S -u 10001 -G app -H -s /sbin/nologin app
WORKDIR /app

COPY --from=build --chown=app:app /workspace/extracted/dependencies/ ./
COPY --from=build --chown=app:app /workspace/extracted/spring-boot-loader/ ./
COPY --from=build --chown=app:app /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /workspace/extracted/application/ ./
# Not root (ADR 0037): the process owns nothing outside /app and /tmp.
USER app

EXPOSE 8080
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
