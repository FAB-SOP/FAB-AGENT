# Spring backend — fab-agent/compose.yaml이 빌드한다. 데이터 팩 · RAG 원문은 이미지에 넣지 않고 /bundle로 마운트한다
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon -q dependencies > /dev/null
COPY src ./src
RUN ./gradlew --no-daemon -q bootJar -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/build/libs/*.jar app.jar
RUN apt-get update -qq && apt-get install -y -qq --no-install-recommends curl > /dev/null && rm -rf /var/lib/apt/lists/*
ENV FAB_PACK_DIR=/bundle/fab-causal-pack-v2 FAB_RAG_DIR=/bundle/fab-rag-real-documents-v1 PORT=8080
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
