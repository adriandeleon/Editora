# Sample Dockerfile — highlighting (source.dockerfile) + the docker-langserver LSP.
# Matched by filename (no extension), a multi-stage build.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app
COPY . .
RUN ./mvnw -q -DskipTests package

FROM eclipse-temurin:25-jre
WORKDIR /app
ENV APP_PORT=8080
COPY --from=build /app/target/app.jar app.jar
EXPOSE ${APP_PORT}
ENTRYPOINT ["java", "-jar", "app.jar"]
