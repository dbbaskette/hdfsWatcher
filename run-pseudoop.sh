#!/bin/bash

# Create storage directory if it doesn't exist
STORAGE_DIR="/tmp/hdfsWatcher"
mkdir -p "$STORAGE_DIR"

# Build the application
mvn clean package -DskipTests

# Run the application in pseudoop mode
java -jar target/hdfsWatcher.jar \
  --hdfswatcher.pseudoop=true \
  --hdfswatcher.local-storage-path="$STORAGE_DIR" \
  --server.port=8080
