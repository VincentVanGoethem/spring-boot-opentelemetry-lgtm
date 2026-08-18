#!/usr/bin/env bash
# Builds both container images from their Dockerfiles. Each Dockerfile is multi-stage: it runs the
# Maven build itself, so nothing needs to be built locally first and the images are reproducible
# from a clean checkout.
#
# The OpenTelemetry javaagent comes along automatically — the maven-dependency-plugin execution in
# each pom is bound to 'validate', so the `mvnw package` in the build stage puts the pinned agent
# in target/, and the runtime stage copies it into the image.
set -euo pipefail

cd "$(dirname "$0")"

docker build -t order-service order-service
docker build -t mystery-box-service mystery-box-service

echo
docker image ls --format 'table {{.Repository}}\t{{.Tag}}\t{{.Size}}' \
  --filter reference=order-service --filter reference=mystery-box-service
