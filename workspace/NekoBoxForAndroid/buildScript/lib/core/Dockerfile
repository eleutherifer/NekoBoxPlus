# syntax=docker/dockerfile:1.7

ARG DEBIAN_VERSION=bookworm
ARG BOOTSTRAP_GO_VERSION=1.26.6

FROM debian:${DEBIAN_VERSION} AS go-builder

ARG TARGETARCH
ARG GO_VERSION=1.27.0
ARG BOOTSTRAP_GO_VERSION=1.26.6
ARG GO_PATCH_DIR=buildScript/lib/core/go-runtime-patches

ENV GO_VERSION=${GO_VERSION} \
    BOOTSTRAP_GO_VERSION=${BOOTSTRAP_GO_VERSION} \
    GOROOT_BOOTSTRAP=/usr/local/go-bootstrap \
    GOROOT=/usr/local/go \
    PATH=/usr/local/go/bin:/usr/local/go-bootstrap/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

RUN apt-get update && apt-get install -y --no-install-recommends \
      ca-certificates curl git patch bash make gcc g++ libc6-dev pkg-config xz-utils \
    && rm -rf /var/lib/apt/lists/*

RUN set -eux; \
    case "${TARGETARCH:-amd64}" in \
      amd64) go_arch=amd64 ;; \
      arm64) go_arch=arm64 ;; \
      *) echo "Unsupported Docker TARGETARCH=${TARGETARCH}" >&2; exit 1 ;; \
    esac; \
    curl -fsSL "https://go.dev/dl/go${BOOTSTRAP_GO_VERSION}.linux-${go_arch}.tar.gz" -o /tmp/go-bootstrap.tar.gz; \
    mkdir -p /usr/local; \
    tar -C /usr/local -xzf /tmp/go-bootstrap.tar.gz; \
    mv /usr/local/go /usr/local/go-bootstrap; \
    rm /tmp/go-bootstrap.tar.gz

WORKDIR /tmp/go-src
COPY ${GO_PATCH_DIR} /tmp/go-runtime-patches

RUN set -eux; \
    curl -fsSL "https://go.dev/dl/go${GO_VERSION}.src.tar.gz" -o /tmp/go-src.tar.gz; \
    tar -C /tmp/go-src --strip-components=1 -xzf /tmp/go-src.tar.gz; \
    for patch_file in /tmp/go-runtime-patches/*.patch; do \
      echo "Applying ${patch_file}"; \
      patch -p1 < "${patch_file}"; \
    done; \
    cd src; \
    ./make.bash; \
    cd /; \
    rm -rf /usr/local/go; \
    mv /tmp/go-src /usr/local/go; \
    rm -rf /tmp/go-src.tar.gz /tmp/go-runtime-patches /root/.cache

FROM debian:${DEBIAN_VERSION}

ARG GO_VERSION=1.27.0
ARG ANDROID_CMDLINE_TOOLS_VERSION=11076708
ARG ADBLOCK_RUST_TOOLCHAIN=1.97.0

ENV GO_VERSION=${GO_VERSION} \
    GOROOT=/usr/local/go \
    GOPATH=/go \
    GOMODCACHE=/go/pkg/mod \
    GOCACHE=/go-cache \
    ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
    CGO_ENABLED=1 \
    GO386=softfloat \
    PATH=/root/.cargo/bin:/usr/local/go/bin:/go/bin:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

RUN apt-get update && apt-get install -y --no-install-recommends \
      bash ca-certificates curl file git make patch unzip zip \
      gcc g++ libc6-dev pkg-config openssh-client \
      nodejs npm \
      openjdk-17-jdk-headless \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /workspace /go /go-cache /opt/android-sdk \
    && chmod -R 0777 /go /go-cache /workspace

COPY --from=go-builder /usr/local/go /usr/local/go

RUN set -eux; \
    curl -fsSL https://sh.rustup.rs | sh -s -- -y --profile minimal --default-toolchain "${ADBLOCK_RUST_TOOLCHAIN}"; \
    rustup target add \
      armv7-linux-androideabi \
      aarch64-linux-android \
      i686-linux-android \
      x86_64-linux-android; \
    cargo --version; \
    rustc --version; \
    node --version; \
    npm --version

RUN go version && which go && go env GOROOT GOPATH

# Optional fallback: if the host does not bind-mount an SDK at /opt/android-sdk,
# install command-line tools only. The wrapper mounts the real SDK by default.
RUN set -eux; \
    mkdir -p /opt/android-sdk/cmdline-tools; \
    curl -fsSL --retry 5 --retry-all-errors --retry-delay 2 "https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_CMDLINE_TOOLS_VERSION}_latest.zip" -o /tmp/android-cmdline-tools.zip; \
    unzip -q /tmp/android-cmdline-tools.zip -d /tmp/android-cmdline-tools; \
    mv /tmp/android-cmdline-tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest; \
    rm -rf /tmp/android-cmdline-tools /tmp/android-cmdline-tools.zip

WORKDIR /workspace/project

CMD ["bash", "buildScript/lib/core.sh"]
