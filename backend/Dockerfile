# Spring backend — ai/compose.yaml이 빌드한다. 데이터 팩 · RAG 원문은 이미지에 넣지 않고 /bundle로 마운트한다
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
# 묶음 경로(FAB_PACK_DIR · FAB_RAG_DIR)는 compose가 넣는다 — 이미지에는 기본값을 두지 않는다
ENV PORT=8080
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
