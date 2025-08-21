# HdfsWatcher Processing Control API

This document describes the API endpoints for controlling the processing state of the hdfsWatcher application.

## Overview

The API allows you to control whether the hdfsWatcher application processes files from HDFS or local storage and publishes them to the RabbitMQ queue. The application defaults to **stopped** state to help control demo timing.

## Base URL

When running with default settings:
```
http://localhost:8080/api
```

## Core Processing Control Endpoints

### 1. GET /api/processing/state

**Purpose**: Get the current processing state

**Example Request**:
```bash
curl -X GET http://localhost:8080/api/processing/state
```

**Example Response**:
```json
{
  "enabled": true,
  "status": "STARTED",
  "consumerStatus": "CONSUMING",
  "lastChanged": "2025-01-01T12:34:56.789Z",
  "lastChangeReason": "Application startup",
  "timestamp": "2025-01-01T12:35:00.123Z"
}
```

### 2. POST /api/processing/start

**Purpose**: Start/enable file processing (also processes pending files immediately)

**Example Request**:
```bash
curl -X POST http://localhost:8080/api/processing/start
```

**Example Response**:
```json
{
  "success": true,
  "message": "Processing started successfully and 3 pending files were processed immediately",
  "stateChanged": true,
  "enabled": true,
  "status": "STARTED",
  "consumerStatus": "CONSUMING",
  "immediatelyProcessedCount": 3,
  "lastChanged": "2025-01-01T12:35:10.456Z",
  "timestamp": "2025-01-01T12:35:10.456Z"
}
```

### 3. POST /api/processing/stop

**Purpose**: Stop/disable file processing

**Example Request**:
```bash
curl -X POST http://localhost:8080/api/processing/stop
```

**Example Response**:
```json
{
  "success": true,
  "message": "Processing stopped successfully. Files will remain in storage.",
  "stateChanged": true,
  "enabled": false,
  "status": "STOPPED",
  "consumerStatus": "IDLE",
  "lastChanged": "2025-01-01T12:35:20.789Z",
  "timestamp": "2025-01-01T12:35:20.789Z"
}
```

### 4. POST /api/processing/toggle

**Purpose**: Toggle processing state (if enabled → disable, if disabled → enable)

**Example Request**:
```bash
curl -X POST http://localhost:8080/api/processing/toggle
```

**Example Response (when toggling from disabled to enabled)**:
```json
{
  "success": true,
  "message": "Processing started successfully. Previous state: disabled, Current state: enabled. 2 pending files were processed immediately.",
  "action": "started",
  "previousState": {
    "enabled": false,
    "status": "STOPPED"
  },
  "currentState": {
    "enabled": true,
    "status": "STARTED",
    "consumerStatus": "CONSUMING"
  },
  "immediatelyProcessedCount": 2,
  "lastChanged": "2025-01-01T12:35:30.123Z",
  "timestamp": "2025-01-01T12:35:30.123Z"
}
```

## File Management Endpoints

### 5. GET /api/status

**Purpose**: Get comprehensive application status including file counts and processing state

**Example Request**:
```bash
curl -X GET http://localhost:8080/api/status
```

**Example Response**:
```json
{
  "status": "success",
  "mode": "standalone",
  "isLocalMode": true,
  "hdfsDisconnected": false,
  "totalFiles": 5,
  "processedFilesCount": 3,
  "processedFilesHashes": ["hash1", "hash2", "hash3"],
  "enabled": true,
  "status": "STARTED",
  "consumerStatus": "CONSUMING",
  "timestamp": "2025-01-01T12:35:00.123Z"
}
```

### 6. GET /api/files

**Purpose**: Get detailed file listing with processing status

**Example Request**:
```bash
curl -X GET http://localhost:8080/api/files
```

**Example Response**:
```json
{
  "status": "success",
  "files": [
    {
      "name": "document1.pdf",
      "size": 1024576,
      "type": "file",
      "state": "processed",
      "url": "http://localhost:8080/api/files/document1.pdf",
      "source": "local"
    },
    {
      "name": "document2.pdf",
      "size": 2048576,
      "type": "file", 
      "state": "pending",
      "url": "http://localhost:8080/api/files/document2.pdf",
      "source": "local"
    }
  ],
  "totalFiles": 2,
  "hdfsDisconnected": false,
  "mode": "standalone",
  "enabled": true,
  "status": "STARTED",
  "consumerStatus": "CONSUMING",
  "timestamp": "2025-01-01T12:35:00.123Z"
}
```

### 7. POST /api/files/upload

**Purpose**: Upload a new file to the system

**Example Request**:
```bash
curl -X POST -F "file=@document.pdf" http://localhost:8080/api/files/upload
```

**Example Response**:
```json
{
  "status": "success",
  "filename": "document.pdf",
  "url": "http://localhost:8080/api/files/document.pdf",
  "timestamp": "2025-01-01T12:35:00.123Z"
}
```

## File Processing Control Endpoints

### 8. POST /api/reprocess

**Purpose**: Mark specific files for reprocessing by clearing their processed status

**Example Request**:
```bash
curl -X POST http://localhost:8080/api/reprocess \
  -H "Content-Type: application/json" \
  -d '{"fileHashes": ["hash1", "hash2"]}'
```

**Example Response**:
```json
{
  "status": "success",
  "reprocessedCount": 2,
  "reprocessedHashes": ["hash1", "hash2"],
  "message": "Successfully marked 2 files for reprocessing",
  "timestamp": "2025-01-01T12:35:00.123Z"
}
```

### 9. POST /api/process-now

**Purpose**: Immediately process specific files without waiting for the next scan cycle

**Example Request**:
```bash
curl -X POST http://localhost:8080/api/process-now \
  -H "Content-Type: application/json" \
  -d '{"fileHashes": ["hash1", "hash2"]}'
```

**Example Response**:
```json
{
  "status": "success",
  "processedCount": 2,
  "processedHashes": ["hash1", "hash2"],
  "failedHashes": [],
  "message": "Successfully processed 2 files immediately",
  "timestamp": "2025-01-01T12:35:00.123Z"
}
```

### 10. POST /api/clear

**Purpose**: Clear all processed files tracking

**Example Request**:
```bash
curl -X POST http://localhost:8080/api/clear
```

**Example Response**:
```json
{
  "status": "success",
  "clearedCount": 5,
  "message": "Successfully cleared 5 processed files",
  "timestamp": "2025-01-01T12:35:00.123Z"
}
```

### 11. POST /api/reprocess-all

**Purpose**: Stop processing and clear all processed flags (full reset)

**Example Request**:
```bash
curl -X POST http://localhost:8080/api/reprocess-all
```

**Example Response**:
```json
{
  "status": "success",
  "enabled": false,
  "status": "STOPPED",
  "consumerStatus": "IDLE",
  "clearedCount": 5,
  "message": "Processing stopped and 5 processed files cleared",
  "timestamp": "2025-01-01T12:35:00.123Z"
}
```

## Behavior Details

### When Processing is ENABLED
- **Status**: `"STARTED"`
- **Enabled**: `true`
- **Consumer Status**: `"CONSUMING"`
- **Behavior**: Application scans for files and publishes them to RabbitMQ queue
- **Auto-processing**: When enabled, immediately processes any pending files

### When Processing is DISABLED
- **Status**: `"STOPPED"`
- **Enabled**: `false`
- **Consumer Status**: `"IDLE"`
- **Behavior**: Application ignores files during scans, no queue publishing
- **Queue**: Files remain unprocessed until processing is re-enabled

## Response Field Explanations

| Field | Description |
|-------|-------------|
| `enabled` | Boolean indicating if processing is enabled |
| `status` | String status: `"STARTED"` or `"STOPPED"` |
| `consumerStatus` | String indicating queue behavior: `"CONSUMING"` or `"IDLE"` |
| `lastChanged` | ISO 8601 timestamp of last state change |
| `lastChangeReason` | Human-readable reason for the last state change |
| `timestamp` | ISO 8601 timestamp of the API response |
| `stateChanged` | Boolean indicating if the API call changed the state |
| `action` | (toggle only) Action performed: `"started"` or `"stopped"` |
| `previousState` | (toggle only) State before the toggle |
| `currentState` | (toggle only) State after the toggle |
| `immediatelyProcessedCount` | Number of pending files processed when enabling |
| `totalFiles` | Total number of files found in storage |
| `processedFilesCount` | Number of files that have been processed |
| `state` | File processing state: `"processed"` or `"pending"` |
| `source` | File source: `"local"` or HDFS directory name |

## Key Features

1. **Default Stopped State**: hdfsWatcher defaults to disabled for demo control
2. **Immediate Processing**: When enabling, automatically processes pending files
3. **File Tracking**: Maintains processed file hashes to avoid duplicates
4. **Dual Mode**: Supports both local storage and HDFS backends
5. **Granular Control**: Can process specific files immediately or reprocess files
6. **Full Reset**: Can clear all processing state and start fresh

## Error Handling

All endpoints return appropriate HTTP status codes:
- `200 OK`: Successful operation
- `400 Bad Request`: Invalid request data
- `500 Internal Server Error`: Server-side error

Error responses include:
```json
{
  "status": "error",
  "message": "Description of the error",
  "timestamp": "2025-01-01T12:35:00.123Z"
}
```
