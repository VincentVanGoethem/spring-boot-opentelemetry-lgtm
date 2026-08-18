# How the mystery-box-service image is assembled — deliberately here and not in the pom. The project
# does not use spring-boot:build-image / Paketo buildpacks, so packaging is plain `docker build`.
#
#   docker build -t mystery-box-service mystery-box-service   (see ../build-images.sh)
#
# The image owns three things: the JRE, the application jar, and the OpenTelemetry javaagent —
# downloaded here, attached here, and configured here through the JVM arguments in the ENTRYPOINT.
#
# It deliberately does NOT own the agent's configuration: otel-agent.properties is mounted at /otel
# by docker-compose.yaml, so what gets collected changes with a restart instead of a rebuild.
# (The Spring-side settings — actuator, Micrometer aspects, log pattern — live in the jar's
# application.yaml, because Spring is what reads them.)

# --- build ---------------------------------------------------------------------------------
FROM eclipse-temurin:25-jdk AS build
WORKDIR /build

# Resolve dependencies in their own layer, so editing src/ does not re-download the world.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline

COPY src/ src/
RUN ./mvnw -B -DskipTests package

# --- runtime -------------------------------------------------------------------------------
FROM eclipse-temurin:25-jre
WORKDIR /app

# The one place the agent version is pinned.
ARG OTEL_AGENT_VERSION=2.30.0
# Create the directory first: ADD --chmod applies the mode to directories it creates implicitly as
# well, which would leave /opt/otel at 644 — no execute bit, so the non-root user below cannot
# traverse into it and the JVM fails to open the agent.
RUN mkdir -p /opt/otel
ADD --chmod=644 \
    https://repo1.maven.org/maven2/io/opentelemetry/javaagent/opentelemetry-javaagent/${OTEL_AGENT_VERSION}/opentelemetry-javaagent-${OTEL_AGENT_VERSION}.jar \
    /opt/otel/opentelemetry-javaagent.jar

COPY --from=build /build/target/mystery-box-service-*.jar /app/application.jar

RUN useradd --system --uid 1001 --gid root appuser
USER 1001
EXPOSE 8081

# The JVM arguments live in the image — the pom builds the jar and nothing more. These are
# properties of *this image*, so they are fixed here rather than repeated in every deployment:
#   -javaagent                          the agent is part of the runtime
#   otel.javaagent.configuration-file   where the mounted agent config will be
#   otel.service.name                   this image is mystery-box-service, always
#   otel.semconv-stability.opt-in       must be a -D or env var; the agent reads it before the
#                                       configuration file is loaded
#
# What is NOT here: otel.exporter.otlp.endpoint. Where telemetry is shipped differs per
# environment, so it stays an OTEL_EXPORTER_OTLP_ENDPOINT variable in docker-compose.yaml.
# Note the asymmetry that makes this matter — for the agent, -D beats OTEL_*, so anything baked
# in here can no longer be overridden by an environment variable at run time.
ENTRYPOINT ["java", \
  "-javaagent:/opt/otel/opentelemetry-javaagent.jar", \
  "-Dotel.javaagent.configuration-file=/otel/otel-agent.properties", \
  "-Dotel.service.name=mystery-box-service", \
  "-Dotel.semconv-stability.opt-in=database", \
  "-jar", "/app/application.jar"]
