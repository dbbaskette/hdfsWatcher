# HDFS Watcher

<div align="center">
  <img src="images/hdfswatcher.png" alt="HDFS Watcher Logo" width="300">
  <h1>HDFS Watcher</h1>
  <p>⚡ API-only Spring Boot service that monitors HDFS and emits file URLs for your RAG/data pipelines.</p>

  <a href="https://www.java.com/"><img alt="Java" src="https://img.shields.io/badge/Java-21-007396?logo=java&logoColor=white"></a>
  <a href="https://spring.io/projects/spring-boot"><img alt="Spring Boot" src="https://img.shields.io/badge/Spring%20Boot-3.4.5-6DB33F?logo=spring-boot&logoColor=white"></a>
  <a href="https://maven.apache.org/"><img alt="Build" src="https://img.shields.io/badge/Build-Maven-C71A36?logo=apachemaven&logoColor=white"></a>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/badge/License-MIT-blue.svg"></a>
</div>

## 🚀 Features

### Core Functionality
- **HDFS Directory Monitoring**: Monitors HDFS directories for new files
- **WebHDFS URL Generation**: Outputs WebHDFS URLs as JSON messages
- **Multiple Deployment Modes**: 
  - **Standalone**: Logs to console with local file storage
  - **Cloud**: Streams to RabbitMQ/Spring Cloud Data Flow
- **Pseudo-operational Mode**: Local file system support when HDFS is unavailable

### API-Only Interface
- **Processing Controls**: Start/Stop/Toggle processing with immediate execution
- **File Upload API**: Upload via multipart endpoint (up to 512MB)
- **File Listing**: Unified metadata across modes (name, size, type, state, url)
- **Reprocess-All**: Stop + clear processed flags in one call

### Processing Control
- **Start/Stop Controls**: Enable/disable file processing with immediate execution
- **Pending File Processing**: When enabled, immediately processes all pending files
- **Demo Timing Control**: Perfect for controlling demo flow and timing
- **Queue Management**: Control when files are sent to RabbitMQ/SCDF

## 📋 Prerequisites

- **Java 21+** (Spring Boot 3.4.5)
- **Maven 3.6+**
- **HDFS Cluster** (for HDFS mode)
- **RabbitMQ** (for cloud mode with Spring Cloud Data Flow)

## 🏃‍♂️ Quick Start

### 1. Build the Application
```bash
./mvnw clean package -DskipTests spring-boot:repackage
```

### 2. Run in Standalone Mode (Recommended for Testing)
```bash
# Using the provided script
./run-pseudoop.sh

# Or manually
java -jar target/hdfsWatcher-3.10.0.jar \
  --hdfswatcher.pseudoop=true \
  --hdfswatcher.local-storage-path=/tmp/hdfsWatcher \
  --server.port=8080
```

### 3. Run in Cloud Mode
```bash
java -jar target/hdfsWatcher-3.10.0.jar \
  --spring.profiles.active=cloud \
  --hdfswatcher.mode=cloud
```

### 4. Use the API (no UI)
The app no longer serves a browser UI. Use curl or your management service.

### 5. Local runner (with Rabbit auto-setup)

Run locally with Dockerized RabbitMQ and an interactive menu:

```bash
# Pseudoop (local dir), cloud-mode stream + monitoring
./run-local.sh -m cloud -p true -l "$(pwd)/files" -q hdfswatcher-textproc -M -Q pipeline.metrics

# Standalone (console messages) + monitoring to Rabbit
./run-local.sh -m standalone -p true -l "$(pwd)/files" -M -Q pipeline.metrics
```

What it does:
- Starts RabbitMQ container (5672/15672), sets `spring.rabbitmq.host=localhost`
- Auto-declares stream exchange+queue for `output.destination` and binds with `#`
- Auto-declares monitoring queue `pipeline.metrics`
- Starts app, then offers an interactive menu (enable/disable/toggle processing, show status/files)

## 🔧 API Endpoints

### Processing Control
- `GET /api/processing-state` — Get current processing state
- `POST /api/processing/start` — Enable processing and process all pending files now
- `POST /api/processing/stop` — Disable processing
- `POST /api/processing/toggle` — Toggle processing state

### File Management
- `GET /api/files` — List files with metadata
- `POST /api/files/upload` — Upload file (multipart field: `file`)
- `POST /api/reprocess-all` — Stop processing and clear all processed flags
- `POST /api/reprocess` — Mark selected files (by hash) for reprocessing
- `POST /api/clear` — Clear all processed flags (legacy; prefer `/api/reprocess-all`)
- `GET /api/status` — Detailed status

Response for `GET /api/files`:
```json
{
  "status": "success",
  "files": [
    { "name": "file1.txt", "size": 12345, "type": "file", "state": "processed", "url": "..." },
    { "name": "file2.txt", "size": 6789,  "type": "file", "state": "pending",   "url": "..." }
  ],
  "totalFiles": 2,
  "processingEnabled": true,
  "processingState": "enabled",
  "timestamp": 1730745600000
}
```

Notes
- The `url` field is the downstream processing URL.
  - In HDFS mode, it is a WebHDFS URL.
  - In pseudoop mode, it is an app-constructed URL for downstream consumers; this app does not serve file downloads.

## ⚙️ Configuration

### Application Properties

#### Server Configuration
```properties
server.port=${PORT:8080}
server.servlet.context-path=/
spring.servlet.multipart.max-file-size=512MB
spring.servlet.multipart.max-request-size=512MB
```

#### HDFS Mode (Default)
```properties
hdfswatcher.hdfs-uri=hdfs://localhost:9000
hdfswatcher.hdfs-paths=/
hdfswatcher.hdfs-path=/                    # deprecated: use hdfs-paths instead
hdfswatcher.hdfs-user=${USER}
hdfswatcher.poll-interval=60
hdfswatcher.webhdfs-uri=http://localhost:50070
hdfswatcher.mode=standalone
```

**Multiple HDFS Directories**: You can now watch multiple HDFS directories by setting `hdfswatcher.hdfs-paths` to a comma-separated list:
```properties
hdfswatcher.hdfs-paths=/policies,/documents,/reports
```

#### Pseudo-operational Mode
```properties
hdfswatcher.pseudoop=true
hdfswatcher.local-storage-path=/tmp/hdfsWatcher
```

#### Cloud Mode
```properties
hdfswatcher.mode=cloud
spring.cloud.stream.bindings.output.destination=your-queue-name
```

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `HDFSWATCHER_PSEUDOOP` | Enable pseudoop mode | `false` |
| `HDFSWATCHER_LOCAL_STORAGE_PATH` | Local storage directory | `/tmp` |
| `HDFSWATCHER_HDFS_URI` | HDFS NameNode URI | `hdfs://localhost:9000` |
| `HDFSWATCHER_HDFS_PATH` | Directory to watch | `/` |
| `HDFSWATCHER_POLL_INTERVAL` | Polling interval (seconds) | `60` |
| `PORT` | Server port | `8080` |

## 🧪 API Examples

```bash
# Check processing state
curl -s http://localhost:8080/api/processing-state | jq

# Start processing (also processes pending files immediately)
curl -s -X POST http://localhost:8080/api/processing/start | jq

# Upload a file (pseudoop or HDFS mode)
curl -s -F "file=@test.txt" http://localhost:8080/api/files/upload | jq

# List files with normalized metadata
curl -s http://localhost:8080/api/files | jq

# Reprocess all (stop + clear processed flags)
curl -s -X POST http://localhost:8080/api/reprocess-all | jq

# Mark specific files for reprocessing (hashes from GET /api/files in HDFS mode)
curl -s -X POST http://localhost:8080/api/reprocess \
  -H 'Content-Type: application/json' \
  -d '{"fileHashes":["<hash1>","<hash2>"]}' | jq
```

## 📊 Output Format

### JSON Message (RabbitMQ/SCDF)
```json
{
  "type": "hdfs",
  "url": "webhdfs://localhost:30800/test/yourfile",
  "mode": "cloud"
}
```

### WebHDFS URL Format
```
http://namenode:50070/webhdfs/v1/path/to/file?op=OPEN&user.name=username
```

## 📈 Health & Metrics

Actuator is enabled and exposes health, info, and metrics.

- Health: `GET /actuator/health`
  - Includes `webHdfsService` and `hdfsWatcherOutput` details
- Metrics:
  - `GET /actuator/metrics/hdfswatcher.processing.enabled`
  - `GET /actuator/metrics/hdfswatcher.last.poll.timestamp`

Tip: Use Prometheus or your monitoring stack to scrape these metrics.

## 📡 Monitoring (RabbitMQ)

This app can emit lightweight monitoring messages to a shared RabbitMQ queue for your external UI.

- Enable via properties (kebab-case shown):
  - `app.monitoring.rabbitmq-enabled=true`
  - `app.monitoring.queue-name=pipeline.metrics`
  - `app.monitoring.instance-id=hdfsWatcher-0` (optional)
  - `app.monitoring.emit-interval-seconds=10`
  - `app.monitoring.rabbitmq-auto-declare=true` (local convenience)
  - Plus your Rabbit connection: `spring.rabbitmq.*`

- Behavior:
  - On startup: sends an INIT message immediately with minimal fields
  - Then every N seconds: sends a heartbeat with extended fields

INIT message example
```json
{
  "instanceId": "hdfsWatcher-0",
  "timestamp": "2025-08-08T14:23:45Z",
  "event": "INIT",
  "status": "DISABLED",
  "uptime": "0s",
  "hostname": "ip-10-0-1-23.ec2.internal",
  "publicHostname": "hdfswatcher-blue.cfapps.io",
  "port": 8080,
  "internalUrl": "http://ip-10-0-1-23.ec2.internal:8080",
  "publicUrl": "https://hdfswatcher-blue.cfapps.io",
  "publicPort": 443,
  "url": "https://hdfswatcher-blue.cfapps.io",
  "meta": { "service": "hdfsWatcher", "bindingState": "stopped", "inputMode": "cloud" }
}
```

Heartbeat example
```json
{
  "instanceId": "hdfsWatcher-0",
  "timestamp": "2025-08-08T14:25:15Z",
  "event": "HEARTBEAT",
  "status": "PROCESSING",
  "uptime": "0h 1m 30s",
  "hostname": "ip-10-0-1-23.ec2.internal",
  "publicHostname": "hdfswatcher-blue.cfapps.io",
  "port": 8080,
  "internalUrl": "http://ip-10-0-1-23.ec2.internal:8080",
  "publicUrl": "https://hdfswatcher-blue.cfapps.io",
  "publicPort": 443,
  "url": "https://hdfswatcher-blue.cfapps.io",
  "currentFile": null,
  "filesProcessed": 0,
  "filesTotal": 0,
  "totalChunks": 0,
  "processedChunks": 0,
  "processingRate": 0,
  "errorCount": 0,
  "lastError": null,
  "memoryUsedMB": 420,
  "pendingMessages": 0,
  "meta": { "service": "hdfsWatcher", "bindingState": "running", "inputMode": "cloud" }
}
```

## 🏗️ Architecture

### Core Components
- **HdfsWatcherService**: Scheduled polling and file processing
- **ProcessingStateService**: Start/stop control management
- **ProcessedFilesService**: File tracking and deduplication
- **FileUploadController**: API endpoints only (no web UI)
- **WebHdfsService**: HDFS operations via WebHDFS
- **LocalFileService**: Local file storage operations

### Processing Flow
1. **File Detection**: Scheduled polling detects new files
2. **Processing Check**: Verifies if processing is enabled
3. **Queue Send**: Sends file URL to RabbitMQ/SCDF
4. **Status Update**: Marks file as processed
5. **External UI**: Managed separately (this app is headless/API-only)

## 🚀 Deployment

### Local Development
```bash
./run-pseudoop.sh
```

### Cloud Foundry
```bash
cf push hdfsWatcher --random-route
```

### Spring Cloud Data Flow (SCDF) app properties mapping

When deploying as a Source named `hdfsWatcher`, configure HDFS and routing like this:

```yaml
# hdfsWatcher (Source) Configuration
app.hdfsWatcher.hdfsWatcher.hdfsUser: "hdfs"
app.hdfsWatcher.hdfsWatcher.hdfsUri: "hdfs://<namenode-host>:8020"
app.hdfsWatcher.hdfsWatcher.hdfsPaths: "/policies,/documents,/reports"  # Multiple directories
app.hdfsWatcher.hdfsWatcher.hdfsPath: "/policies"                       # deprecated: use hdfsPaths
app.hdfsWatcher.hdfsWatcher.webhdfsUri: "http://<namenode-host>:9870"
app.hdfsWatcher.hdfsWatcher.pseudoop: "false"
app.hdfsWatcher.hdfsWatcher.pollInterval: "5"           # seconds
app.hdfsWatcher.spring.profiles.active: "cloud"
app.hdfsWatcher.spring.cloud.config.enabled: "false"
app.hdfsWatcher.spring.cloud.stream.bindings.output.destination: "hdfswatcher-textproc"

# Optional: enable monitoring publisher
app.hdfsWatcher.app.monitoring.rabbitmq-enabled: "true"
app.hdfsWatcher.app.monitoring.queue-name: "pipeline.metrics"
app.hdfsWatcher.app.monitoring.emit-interval-seconds: "10"

# Recommended for CF/SCDF (disable local auto-declares)
app.hdfsWatcher.app.stream.auto-declare: "false"
app.hdfsWatcher.app.monitoring.rabbitmq-auto-declare: "false"
```

### Docker
```bash
docker build -t hdfsWatcher .
docker run -p 8080:8080 hdfsWatcher
```

## 🧪 Testing

### Manual Testing
1. Start application in pseudoop mode
2. Upload files via web interface
3. Toggle processing controls
4. Verify file processing and queue output

### API Testing
See examples above.

## 🔍 Troubleshooting

### Common Issues
- **HDFS Connection**: Use pseudoop mode for local testing
- **File Processing**: Check processing state via web interface
- **Queue Issues**: Verify RabbitMQ connection in cloud mode
- **Large Files**: Ensure sufficient memory for 512MB uploads

### Logs
- Application logs show processing status and file operations
- API endpoints return detailed error messages

## 📝 License

MIT License - see LICENSE file for details.

## 🤝 Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests if applicable
5. Submit a pull request

---

**HDFS Watcher** — Headless, API-only HDFS monitoring service for modern data and RAG pipelines. ✨
