# syntax=docker/dockerfile:1

# --- Etapa 1: compilar o JAR com o JDK ---
FROM eclipse-temurin:25.0.3_9-jdk-noble AS build
WORKDIR /workspace

# Wrapper e scripts de build primeiro; o código-fonte por último.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY src src

# A cache do Gradle fica fora da imagem (cache do BuildKit), só acelera builds seguintes.
RUN --mount=type=cache,target=/root/.gradle \
    chmod +x gradlew && ./gradlew --no-daemon bootJar

# --- Etapa 2: imagem final só com o JRE e o JAR ---
FROM eclipse-temurin:25.0.3_9-jre-noble

RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app
COPY --from=build /workspace/build/libs/messaging-service.jar app.jar

USER app
EXPOSE 8085

# A heap ajusta-se ao limite de memória do contentor; o processo termina se ficar sem memória
# (o Docker reinicia-o), em vez de continuar num estado degradado.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
