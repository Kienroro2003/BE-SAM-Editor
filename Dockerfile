FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace

COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw

# Prime dependency cache first for faster incremental builds.
RUN ./mvnw -DskipTests dependency:go-offline

COPY src src
RUN ./mvnw -DskipTests clean package

FROM eclipse-temurin:17-jdk AS runtime
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends docker.io \
    && rm -rf /var/lib/apt/lists/*

RUN mkdir -p /app/workspace-storage

COPY --from=build /workspace/target/BE-SAM-Editor-*.jar /app/app.jar

EXPOSE 8080

ENV APP_WORKSPACE_STORAGE_ROOT=/app/workspace-storage

# JavaSourceAnalyzer uses the JDK compiler/tree APIs at runtime,
# and sandbox coverage execution shells out to the Docker CLI.
ENTRYPOINT ["sh", "-c", "java --add-modules=jdk.compiler ${JAVA_OPTS} -jar /app/app.jar"]
