# 영상 변환 일꾼 이미지 — 작은 Kotlin 프로그램 + ffmpeg. 같은 이미지가 홈서버 컨테이너 · Cloud Run 작업 · 아무 기계(+ 토큰)에서 돈다
# (어디서 도는지는 환경변수 VIDEO_WORKER_* 가 정한다 — docs/modules/video.md). 빌드: 프로그램을 먼저 만들고 이 폴더를 컨텍스트로
#   ./gradlew :apps:video-worker:installDist
#   docker build -t video-worker apps/video-worker                           # Cloud Run 에 올릴 때는 --platform linux/amd64 를 더한다
# ffmpeg 버전 고정: 베이스 이미지는 **다이제스트**로(AMD64 · ARM64 를 묶은 인덱스 — 2026-10-05 기준 Ubuntu 24.04 noble), ffmpeg 는 apt **버전 명시**(7:6.1.1-3ubuntu5 = ffmpeg 6.1.1).
# 올릴 때는 두 줄을 함께 — 다이제스트를 올리면 `docker run --rm <이미지> apt-cache policy ffmpeg` 로 후보 버전을 보고 ARG 를 맞춘다. 그 뒤 일꾼 시험(FfmpegIntegrationTest 의 가장자리 사례)을 이 버전으로 다시 돌린다.
# 버전이 저장소에서 사라지면 빌드가 실패한다 — 조용히 다른 버전이 들어가는 것보다 낫다.
# wget: 홈서버 배포 계약의 헬스체크가 컨테이너 안에서 `wget` 으로 /health 를 읽는다(docs/deploy.md) — 베이스 이미지에는 없다.
FROM eclipse-temurin:21-jre-noble@sha256:000fd431958bc81a24abe1e8e5f0f0fd3ae365a594bd50aadb20696805f9408c

ARG FFMPEG_VERSION=7:6.1.1-3ubuntu5
RUN apt-get update \
 && apt-get install -y --no-install-recommends ffmpeg=${FFMPEG_VERSION} wget \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --no-create-home worker \
 && mkdir /work && chown worker /work

COPY build/install/video-worker/lib /app/lib

# 비루트 · 읽기 전용 루트 파일시스템으로 돌리는 것을 전제로 한다(임시 폴더 /work 만 쓴다 — 실행 쪽이 tmpfs 또는 볼륨으로 마운트)
USER worker
ENV VIDEO_WORKER_WORKDIR=/work
# 상주(poll) 모양의 /health — VIDEO_WORKER_HEALTH_PORT(기본 8080). once 모양(Cloud Run 작업)은 띄우지 않는다
EXPOSE 8080
ENTRYPOINT ["java", "-Xmx96m", "-Djdk.httpclient.allowRestrictedHeaders=host", "-cp", "/app/lib/*", "dev.sumin.skeleton.videoworker.MainKt"]
