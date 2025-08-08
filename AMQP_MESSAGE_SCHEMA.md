# RAGMon AMQP Message Schema

This document defines the AMQP message format that applications should send to RAGMon for monitoring and management.

## 📋 **Message Schema**

### **Required Fields**
```json
{
  "instanceId": "string",     // Unique instance identifier (e.g., "myApp-12345@hostname.local")
  "timestamp": "string|number", // ISO 8601 datetime or epoch milliseconds
  "event": "string",          // Event type: "INIT", "HEARTBEAT", "ERROR", etc.
  "status": "string",         // Service status (see Status Values below)
  "url": "string",            // Complete service URL with port (e.g., "http://localhost:8081")
  "meta": {
    "service": "string",      // Application/service name (becomes "App" in RAGMon UI)
    "processingStage": "string" // Current processing stage (becomes "Stage" in RAGMon UI)
  }
}
```

### **Optional Fields**
```json
{
  "uptime": "string",           // Human-readable uptime (e.g., "2h 15m 30s")
  "hostname": "string",         // Server hostname (fallback if url not provided)
  "publicHostname": "string",   // Public hostname (fallback if url not provided)
  
  // File Processing Metrics
  "currentFile": "string|null", // Currently processing file
  "filesProcessed": "number",   // Total files processed
  "filesTotal": "number",       // Total files to process
  "totalChunks": "number",      // Total chunks to process
  "processedChunks": "number",  // Chunks processed so far
  "processingRate": "number",   // Processing rate (files/sec or chunks/sec)
  "filename": "string|null",    // Current filename being processed
  
  // Error Tracking
  "errorCount": "number",       // Total error count
  "lastError": "string|null",   // Last error message
  
  // System Metrics
  "memoryUsedMB": "number",     // Memory usage in MB
  "pendingMessages": "number|null", // Pending message count
  
  // Instance Info
  "bootEpoch": "number",        // Instance boot time (epoch milliseconds)
  "version": "string",          // Application version
  
  // Additional metadata in meta object
  "meta": {
    "inputMode": "string",      // Additional context (e.g., "cloud", "local")
    // ... any other custom fields
  }
}
```

## 🎯 **Status Values**

Use these standardized status values for consistent UI display:

```json
{
  "status": "RUNNING",    // ✅ Service is running and healthy (Green indicators)
  "status": "PROCESSING", // ✅ Actively processing work (Green indicators)  
  "status": "IDLE",       // 🟡 Running but no work to do (Yellow indicators)
  "status": "ERROR",      // ❌ Encountering errors (Red indicators)
  "status": "STARTING",   // 🟡 During startup (Yellow indicators)
  "status": "STOPPING"    // 🟡 During shutdown (Yellow indicators)
}
```

> **Note**: RAGMon automatically sets status to `"OFFLINE"` if no messages are received within 30 seconds.

## 📊 **Processing Stage Values**

Use clear, descriptive stage names:

```json
{
  "meta": {
    "processingStage": "watching",    // Monitoring for changes
    "processingStage": "scanning",    // Scanning directories/files
    "processingStage": "processing",  // Actively processing files
    "processingStage": "uploading",   // Uploading/transferring data
    "processingStage": "idle",        // No work to do
    "processingStage": "error",       // Error state
    "processingStage": "starting",    // Initializing
    "processingStage": "stopping"     // Shutting down
  }
}
```

## 🔄 **Event Types**

Common event types RAGMon recognizes:

```json
{
  "event": "INIT",        // Initial startup message
  "event": "HEARTBEAT",   // Regular health check
  "event": "ERROR",       // Error occurred
  "event": "SHUTDOWN",    // Graceful shutdown
  "event": "RESTART",     // Service restart
  "event": "FILE_START",  // Started processing file
  "event": "FILE_COMPLETE", // Finished processing file
  "event": "CHUNK_COMPLETE" // Finished processing chunk
}
```

## 📝 **Complete Example Messages**

### **INIT Message (Service Startup)**
```json
{
  "instanceId": "hdfsWatcher-68927@ultron-m4.local",
  "timestamp": "2025-08-08T13:18:16.219026-04:00",
  "event": "INIT",
  "status": "STARTING",
  "uptime": "0s",
  "url": "http://localhost:8081",
  "hostname": "localhost",
  "publicHostname": "localhost",
  "bootEpoch": 1723141096219,
  "version": "1.0.0",
  "filesProcessed": 0,
  "filesTotal": 0,
  "errorCount": 0,
  "memoryUsedMB": 95,
  "meta": {
    "service": "hdfsWatcher",
    "processingStage": "starting",
    "inputMode": "cloud"
  }
}
```

### **HEARTBEAT Message (Regular Operation)**
```json
{
  "instanceId": "hdfsWatcher-68927@ultron-m4.local",
  "timestamp": "2025-08-08T13:28:16.219026-04:00",
  "event": "HEARTBEAT",
  "status": "RUNNING",
  "uptime": "0h 10m 42s",
  "url": "http://localhost:8081",
  "hostname": "localhost",
  "publicHostname": "localhost",
  "currentFile": "data/input/file123.txt",
  "filesProcessed": 45,
  "filesTotal": 100,
  "totalChunks": 450,
  "processedChunks": 225,
  "processingRate": 2.5,
  "errorCount": 0,
  "lastError": null,
  "memoryUsedMB": 120,
  "pendingMessages": 3,
  "meta": {
    "service": "hdfsWatcher",
    "processingStage": "processing",
    "inputMode": "cloud"
  }
}
```

### **ERROR Message**
```json
{
  "instanceId": "hdfsWatcher-68927@ultron-m4.local",
  "timestamp": "2025-08-08T13:30:16.219026-04:00",
  "event": "ERROR",
  "status": "ERROR",
  "uptime": "0h 12m 42s",
  "url": "http://localhost:8081",
  "hostname": "localhost",
  "publicHostname": "localhost",
  "currentFile": "data/input/corrupted-file.txt",
  "filesProcessed": 48,
  "filesTotal": 100,
  "errorCount": 1,
  "lastError": "Failed to parse file: corrupted-file.txt - Invalid format",
  "memoryUsedMB": 125,
  "meta": {
    "service": "hdfsWatcher",
    "processingStage": "error",
    "inputMode": "cloud"
  }
}
```

## 🚀 **RAGMon Features Enabled by These Messages**

### **Dashboard**
- **Event counts**: Total, Active, Errors
- **Recent events table**: Shows last 50 events with status colors

### **Instances Page**
- **Instance cards**: Shows status, last activity, heartbeat times
- **Service grouping**: Groups instances by service name
- **Status indicators**: Color-coded status dots and badges

### **Applications Page**
- **App management**: Start/stop/toggle controls
- **Health monitoring**: API health checks
- **File management**: Upload, process, reprocess files
- **Real-time status**: Connection and processing state

### **Live Stream**
- **Real-time events**: Live feed of all incoming messages
- **Filtering capabilities**: Pause, search, auto-scroll
- **Debug information**: INIT message discovery panel

## 🔧 **Implementation Notes**

### **Message Frequency**
- **INIT**: Send once on startup
- **HEARTBEAT**: Send every 10-30 seconds during normal operation
- **ERROR**: Send immediately when errors occur
- **SHUTDOWN**: Send once during graceful shutdown

### **URL Construction**
- **Preferred**: Include complete `url` field with protocol and port
- **Fallback**: RAGMon will construct URL from `hostname`/`publicHostname` + default port

### **Timestamp Format**
- **ISO 8601**: `"2025-08-08T13:18:16.219026-04:00"` (preferred)
- **Epoch milliseconds**: `1723141096219` (also supported)

### **Error Handling**
- Continue sending heartbeats even during error states
- Use `status: "ERROR"` and populate `lastError` field
- Increment `errorCount` for tracking

## 📡 **AMQP Configuration**

### **Queue Name**
Default queue: `pipeline.metrics`
(Configurable via `RAGMON_RABBIT_MONITOR_QUEUE` environment variable)

### **Message Format**
- **Content-Type**: `application/json` or `text/plain`
- **Encoding**: UTF-8
- **Routing**: Messages are consumed from the configured queue

---

*This schema ensures your applications integrate seamlessly with RAGMon's monitoring, alerting, and management features.*