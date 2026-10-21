###################
# STAGE 1: builder
###################

FROM node:22-bookworm AS toolchain

# Use the glibc JDK distribution directly instead of depending on Adoptium's apt repository.
# Jammy's JDK is compatible with Bookworm; the Alpine JDK requires musl instead.
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:/root/.local/bin:${PATH}"
COPY --from=eclipse-temurin:25-jdk-jammy /opt/java/openjdk /opt/java/openjdk

WORKDIR /home/node

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl git fontconfig binutils \
    && rm -rf /var/lib/apt/lists/*

RUN java -version && javac -version && node --version

RUN curl -fsSLO --retry 3 https://download.clojure.org/install/linux-install-1.12.0.1488.sh \
    && chmod +x linux-install-1.12.0.1488.sh \
    && ./linux-install-1.12.0.1488.sh \
    && rm linux-install-1.12.0.1488.sh

RUN curl -fsSL --retry 3 https://astral.sh/uv/install.sh -o /tmp/install-uv.sh \
    && sh /tmp/install-uv.sh \
    && rm /tmp/install-uv.sh

RUN clojure -Sdescribe && uv --version

FROM toolchain AS builder

ARG MB_EDITION=oss
ARG VERSION

COPY . .

# Normalize only the container copies; Windows checkouts can supply CRLF scripts.
RUN sed -i 's/\r$//' bin/build.sh bin/check-clojure-cli.sh \
    bin/clear-outdated-cpcaches.sh bin/docker/run_metabase.sh

# version is pulled from git, but git doesn't trust the directory due to different owners
RUN git config --global --add safe.directory /home/node

# install bun for frontend dependencies
RUN npm install -g bun

# install frontend dependencies
RUN bun install --frozen-lockfile

# Source checkouts may store shell scripts as 0644; invoke the build through Bash.
RUN INTERACTIVE=false CI=true MB_EDITION=$MB_EDITION bash bin/build.sh :version ${VERSION}

# ###################
# # STAGE 2: runner
# ###################

## Remember that this runner image needs to be the same as bin/docker/Dockerfile with the exception that this one grabs the
## jar from the previous stage rather than the local build

FROM eclipse-temurin:25-jre-alpine AS runner

ENV FC_LANG=en-US LC_CTYPE=en_US.UTF-8

# copy certs before the RUN so keytool can import them
COPY bin/docker/DigiCertGlobalRootG2.crt.pem /app/certs/DigiCertGlobalRootG2.crt.pem

# dependencies
RUN apk add -U bash fontconfig curl font-noto font-noto-arabic font-noto-hebrew font-noto-cjk java-cacerts && \
    apk upgrade && \
    rm -rf /var/cache/apk/* && \
    mkdir -p /app/certs && \
    curl https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem -o /app/certs/rds-combined-ca-bundle.pem  && \
    /opt/java/openjdk/bin/keytool -noprompt -import -trustcacerts -alias aws-rds -file /app/certs/rds-combined-ca-bundle.pem -keystore /etc/ssl/certs/java/cacerts -keypass changeit -storepass changeit && \
    /opt/java/openjdk/bin/keytool -noprompt -import -trustcacerts -alias azure-cert -file /app/certs/DigiCertGlobalRootG2.crt.pem -keystore /etc/ssl/certs/java/cacerts -keypass changeit -storepass changeit && \
    mkdir -p /plugins && chmod a+rwx /plugins

# add Metabase script and uberjar
COPY --from=builder /home/node/target/uberjar/metabase.jar /app/
COPY --from=builder --chmod=755 /home/node/bin/docker/run_metabase.sh /app/

# expose our default runtime port
EXPOSE 3000

# run it
ENTRYPOINT ["/app/run_metabase.sh"]
