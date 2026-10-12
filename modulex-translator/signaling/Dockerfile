# syntax=docker/dockerfile:1
#
# ModuleX signaling relay. Build from the REPOSITORY ROOT (the Gradle build is multi-project):
#   docker build -f signaling/Dockerfile -t modulex-signaling .
# Only the files copied below are needed; BuildKit transfers just those from the context.

FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle/ gradle/
# settings.gradle.kts includes these modules; their build scripts are needed to configure
# the build, but none of their sources are compiled for :signaling.
COPY core/build.gradle.kts core/
COPY engines/build.gradle.kts engines/
COPY signaling/ signaling/
RUN chmod +x gradlew \
    && ./gradlew --no-daemon --console=plain :signaling:installDist \
    && rm -rf signaling/build/install/signaling/bin/*.bat

FROM eclipse-temurin:17-jre
RUN groupadd --system --gid 10001 relay \
    && useradd --system --uid 10001 --gid relay --no-create-home --shell /usr/sbin/nologin relay
COPY --from=build /src/signaling/build/install/signaling /opt/signaling
ENV PORT=8080 \
    HOST=0.0.0.0 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["/opt/signaling/bin/signaling"]
