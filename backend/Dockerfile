FROM gradle:jdk23 AS build

WORKDIR /app

# Копируем ВСЁ сразу
COPY . .

# Собираем проект
RUN gradle build -x test --no-daemon

FROM eclipse-temurin:23-jre-alpine

WORKDIR /app

# Копируем jar напрямую, без лишних папок
COPY --from=build /app/.build/libs/auth-backend-*.jar app.jar

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=1s --start-period=60s --retries=5 \
  CMD wget  --spider -q http://localhost:8080/health || exit 1

ENV JAVA_TOOL_OPTIONS="-XX:InitialRAMPercentage=75.0 -XX:MaxRAMPercentage=75.0 -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp/dump.hprof"

ENTRYPOINT ["java", "-jar", "app.jar"]