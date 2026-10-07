# 운영 이미지. 실행은 deploy/compose.yaml 에서 한다.
# 테스트는 CI(backend-ci.yml)가 돌린다 — 이미지 빌드에서는 건너뛴다(Testcontainers 가 Docker 를 요구한다).

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
# 의존성 레이어를 소스와 분리해 소스만 바뀌면 다운로드를 다시 하지 않게 한다.
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN ./gradlew --no-daemon dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon bootJar -x test \
 && cp build/libs/gitory-backend-*-SNAPSHOT.jar /src/app.jar

FROM eclipse-temurin:21-jre
RUN useradd --system --no-create-home app
WORKDIR /app
COPY --from=build /src/app.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
