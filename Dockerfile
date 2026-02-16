FROM --platform=linux/amd64 gradle:8.7-jdk17

USER root
RUN apt-get update && apt-get install -y --no-install-recommends \
    wget \
    unzip \
    && rm -rf /var/lib/apt/lists/*

ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV ANDROID_HOME=/opt/android-sdk
ENV PATH=${PATH}:${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:${ANDROID_SDK_ROOT}/platform-tools

RUN mkdir -p ${ANDROID_SDK_ROOT}/cmdline-tools
RUN wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O /tmp/cmdline-tools.zip && \
    unzip -q /tmp/cmdline-tools.zip -d ${ANDROID_SDK_ROOT}/cmdline-tools && \
    mv ${ANDROID_SDK_ROOT}/cmdline-tools/cmdline-tools ${ANDROID_SDK_ROOT}/cmdline-tools/latest && \
    rm -f /tmp/cmdline-tools.zip

RUN yes | sdkmanager --licenses
RUN sdkmanager \
    "platform-tools" \
    "platforms;android-34" \
    "build-tools;34.0.0"

WORKDIR /workspace

COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY app/build.gradle.kts app/proguard-rules.pro ./app/
RUN gradle --no-daemon help

COPY . .

RUN gradle --no-daemon :app:assembleDebug

CMD ["bash", "-lc", "ls -lah app/build/outputs/apk/debug && cp -f app/build/outputs/apk/debug/*.apk /tmp/ || true"]
