#!/bin/bash
# Usage: ./dockerbuild.sh <dockerhub-username> <image-name> [tag]
# Example: ./dockerbuild.sh yourdockerhubuser textproc latest

set -e

DOCKERHUB_USER=dbbaskette
IMAGE_NAME=hdfswatcher
TAG=${3:-latest}

# Build the Spring Boot JAR
./mvnw clean package -DskipTests

# Enable Docker Buildx
if ! docker buildx inspect multiarch-builder > /dev/null 2>&1; then
    docker buildx create --name multiarch-builder --use
else
    docker buildx use multiarch-builder
fi

docker login

docker buildx build --platform linux/amd64,linux/arm64 \
  -t "$DOCKERHUB_USER/$IMAGE_NAME:$TAG" \
  --push .

echo "Image pushed as $DOCKERHUB_USER/$IMAGE_NAME:$TAG for linux/amd64 and linux/arm64"

docker pull $DOCKERHUB_USER/$IMAGE_NAME:$TAG