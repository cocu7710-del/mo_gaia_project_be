# 배포용 (Render 등) — FE 빌드가 src/main/resources/static 에 복사돼 있는 상태에서 빌드한다
# (FE 갱신 시: mo_gaia_project_fe에서 npm run build 후 dist/* 를 static/ 으로 복사하고 커밋)

FROM gradle:8-jdk17 AS build
WORKDIR /app
COPY build.gradle settings.gradle ./
COPY gradle ./gradle
# 의존성 레이어 캐시
RUN gradle dependencies --no-daemon || true
COPY src ./src
RUN gradle bootJar --no-daemon -x test

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
# Render가 PORT 환경변수를 주입 — application.properties의 server.port=${PORT:8080}가 사용
EXPOSE 8080
# 무료 인스턴스(512MB)에서 OOM(137) 방지 — 힙·메타스페이스·스레드 스택 상한 고정(기본값).
# 메모리 여유 있는 환경(오라클 VM 등)은 JAVA_OPTS 환경변수로 오버라이드
ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS:--Xmx256m -XX:MaxMetaspaceSize=160m -Xss512k} -jar app.jar"]
