# syntax=docker/dockerfile:1

# ---------- Stage 1: build the fat jar ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
# Cache dependencies first for faster rebuilds.
COPY pom.xml .
RUN mvn -q -e -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -e -DskipTests clean package
# -> /src/target/news-job-ads-video.jar

# ---------- Stage 2: runtime ----------
# Debian bookworm has genuine `chromium`/`chromium-driver` .deb packages
# (Ubuntu's are snap-only and don't install in a container).
FROM debian:bookworm-slim

# Runtime tools:
#  - openjdk-17-jre   : run the app
#  - ffmpeg           : build/mux the video
#  - espeak-ng        : offline TTS fallback if piper is unavailable
#  - chromium + driver: proof-of-source evidence capture (headless)
#  - fonts            : Java2D slide rendering incl. Indic/Malayalam text
#  - python3-pip      : install piper-tts (preferred Indian-English neural voice)
#  - awscli           : upload the finished video + evidence to S3
#  - curl, ca-certs   : download the piper voice model
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
        openjdk-17-jre-headless \
        fonts-dejavu-core \
        fonts-noto-core \
        fonts-indic \
        ffmpeg \
        espeak-ng \
        chromium \
        chromium-driver \
        fontconfig \
        python3 \
        python3-pip \
        python3-venv \
        awscli \
        curl \
        ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Preferred neural TTS (Indian English). Installed in an isolated venv (Debian's
# system Python is PEP-668 "externally managed"). Best-effort: the app falls back
# to espeak-ng/pico2wave automatically if piper or its model is unavailable.
ENV PATH="/opt/piper-venv/bin:${PATH}"
RUN python3 -m venv /opt/piper-venv && \
    /opt/piper-venv/bin/pip install --no-cache-dir piper-tts \
    || echo "[build] piper-tts install skipped (falling back to espeak-ng)"

# Download the Indian-English piper voice model into the location Narrator looks in.
ENV PIPER_MODEL=/opt/piper-voices/en_IN-spicor-medium.onnx
RUN mkdir -p /opt/piper-voices && \
    curl -fsSL 'https://huggingface.co/navgurukul-ai-labs/text-to-speech-en-IN-piper/resolve/main/en_IN-dataset%3Dspicor-english-base%3Dljspeech-epochs%3D1089.onnx' \
        -o "$PIPER_MODEL" && \
    curl -fsSL 'https://huggingface.co/navgurukul-ai-labs/text-to-speech-en-IN-piper/resolve/main/en_IN-dataset%3Dspicor-english-base%3Dljspeech-epochs%3D1089.onnx.json' \
        -o "$PIPER_MODEL.json" \
    || echo "[build] piper voice model download skipped (falling back to espeak-ng)"

# Point Selenium at the pre-installed Chromium + chromedriver so it does NOT
# try to download a driver at runtime (Debian/Ubuntu paths).
ENV CHROME_BINARY=/usr/bin/chromium
ENV JAVA_TOOL_OPTIONS="-Dwebdriver.chrome.driver=/usr/bin/chromedriver"

WORKDIR /app
COPY --from=build /src/target/news-job-ads-video.jar /app/news-job-ads-video.jar
# Bundle the production source list so scrape mode works out of the box.
COPY src/main/resources/sources.json /app/sources.json
COPY deploy/run-and-upload.sh /app/run-and-upload.sh
RUN chmod +x /app/run-and-upload.sh

ENTRYPOINT ["/app/run-and-upload.sh"]
