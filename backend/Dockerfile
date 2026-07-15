# backend/Dockerfile
FROM eclipse-temurin:17-jdk-alpine AS builder

WORKDIR /app
COPY .mvn .mvn
COPY mvnw mvnw.cmd pom.xml ./
# Pre-fetch dependencies for better layer caching
RUN ./mvnw -q dependency:go-offline || true

COPY src ./src
RUN ./mvnw -q clean package -DskipTests

FROM eclipse-temurin:17-jre-alpine

RUN addgroup -S app && adduser -S app -G app
USER app
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar

EXPOSE 8080

# Provide real values at runtime (docker-compose / K8s secrets); never bake keys in.
ENV DIFY_BASE_URL=https://api.dify.ai/v1 \
    CORS_ORIGINS=http://localhost:3000

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
