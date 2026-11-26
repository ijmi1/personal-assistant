FROM node:22-bookworm-slim AS web
WORKDIR /web
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
COPY src/main ./src/main
COPY --from=web /web/dist ./frontend/dist
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:25-jre-alpine
RUN apk add --no-cache curl fontconfig font-dejavu \
    && addgroup -S -g 10001 assistant \
    && adduser -S -D -u 10001 -G assistant assistant
WORKDIR /app
COPY --from=build /build/target/learning-assistant-*.jar ./app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
