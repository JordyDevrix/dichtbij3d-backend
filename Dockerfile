# syntax=docker/dockerfile:1

# ---------------------------------------------------------------- build stage
FROM --platform=$BUILDPLATFORM docker.io/library/maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q clean package -DskipTests \
 && mv target/$(ls target | grep -E '^dichtbij3d-backend-.*\.jar$' | head -1) /build/app.jar

# ---------------------------------------------------------------- runtime stage
FROM docker.io/library/eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

RUN apk add --no-cache curl \
 && addgroup -S app && adduser -S app -G app \
 && mkdir -p /app/.storage && chown -R app:app /app

COPY --from=build --chown=app:app /build/app.jar /app/app.jar

USER app
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"

HEALTHCHECK --interval=30s --timeout=5s --start-period=45s \
  CMD curl -fsS http://127.0.0.1:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
