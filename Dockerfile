# Adapted from the HyperFrames render image shipped in the npm package:
# hyperframes/dist/docker/Dockerfile.render
FROM node:22-bookworm-slim

ARG HYPERFRAMES_VERSION=0.6.91
ARG BABASHKA_VERSION=1.12.217
ARG TARGETARCH=amd64

RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates curl unzip ffmpeg chromium \
    libgbm1 libnss3 libatk-bridge2.0-0 libdrm2 libxcomposite1 \
    libxdamage1 libxrandr2 libcups2 libasound2 libpangocairo-1.0-0 \
    libxshmfence1 libgtk-3-0 \
    fonts-liberation fonts-noto-color-emoji fonts-noto-cjk fonts-noto-core \
    fonts-noto-extra fonts-noto-ui-core fonts-freefont-ttf fonts-dejavu-core \
    fontconfig \
    && rm -rf /var/lib/apt/lists/* && apt-get clean && fc-cache -fv

ENV PUPPETEER_SKIP_CHROMIUM_DOWNLOAD=true
ENV PUPPETEER_EXECUTABLE_PATH=/usr/bin/chromium
ENV CONTAINER=true
ENV APP_DIR=/app

RUN if [ "$TARGETARCH" = "amd64" ]; then \
      BB_ARCH="amd64"; \
    elif [ "$TARGETARCH" = "arm64" ]; then \
      BB_ARCH="aarch64"; \
    else \
      echo "Unsupported TARGETARCH for Babashka: ${TARGETARCH}" >&2; exit 1; \
    fi; \
    curl -fsSL -o /tmp/babashka.tar.gz \
      "https://github.com/babashka/babashka/releases/download/v${BABASHKA_VERSION}/babashka-${BABASHKA_VERSION}-linux-${BB_ARCH}-static.tar.gz"; \
    tar -xzf /tmp/babashka.tar.gz -C /usr/local/bin bb; \
    rm /tmp/babashka.tar.gz; \
    bb --version

RUN if [ "$TARGETARCH" = "amd64" ]; then \
      npx --yes @puppeteer/browsers install chrome-headless-shell@stable \
        --path /root/.cache/puppeteer; \
    else \
      echo "Skipping chrome-headless-shell install on ${TARGETARCH} (linux64-only); using system chromium."; \
    fi

RUN npm install -g hyperframes@${HYPERFRAMES_VERSION}

RUN SHELL_PATH=$(find /root/.cache/puppeteer/chrome-headless-shell -name "chrome-headless-shell" -type f 2>/dev/null | head -1); \
    if [ -n "$SHELL_PATH" ]; then \
      printf '#!/bin/sh\nexport PRODUCER_HEADLESS_SHELL_PATH=%s\nexec hyperframes render "$@"\n' "$SHELL_PATH" > /usr/local/bin/hf-render; \
    elif [ "$TARGETARCH" = "amd64" ]; then \
      echo "ERROR: chrome-headless-shell binary not found on amd64." >&2; \
      exit 1; \
    else \
      printf '#!/bin/sh\nexec hyperframes render "$@"\n' > /usr/local/bin/hf-render; \
    fi \
    && chmod +x /usr/local/bin/hf-render

WORKDIR /app
COPY src /app/src
COPY templates /app/templates

ENV BABASHKA_CLASSPATH=/app/src
ENTRYPOINT ["bb", "-m", "shortform-subtitles"]
