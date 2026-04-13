#!/usr/bin/env bash
set -euo pipefail

(cd "order-service" && ./mvnw spring-boot:build-image -Dspring-boot.build-image.imageName=order-service -DskipTests)
(cd "mystery-box-service" && ./mvnw spring-boot:build-image -Dspring-boot.build-image.imageName=mystery-box-service -DskipTests)
