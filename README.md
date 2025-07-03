<div align="center">
  <img src="images/hdfswatcher.png" alt="HDFS Watcher Logo" width="300">
  <h1>HDFS Watcher</h1>
  <p>A Spring Boot application that monitors an HDFS directory and outputs new file URLs as JSON messages.</p>
</div>

## Features

- Monitors HDFS directory for new files
- Outputs WebHDFS URLs as JSON
- Supports two modes:
  - **standalone**: Logs to console
  - **cloud**: Streams to RabbitMQ
- Lightweight with minimal dependencies
- **Pseudo-operational Mode**: Local file system support when HDFS is unavailable

## Prerequisites

- Java 11+
- Maven
- Access to HDFS cluster
- (For cloud mode) RabbitMQ

## Quick Start

1. **Build**
   ```sh
   ./mvnw clean package -DskipTests spring-boot:repackage
   ```

2. **Run in Standalone Mode**
   ```sh
   java -jar target/hdfsWatcher-0.1.0-SNAPSHOT.jar --spring.profiles.active=standalone
   ```

3. **Run in Cloud Mode**
   ```sh
   java -jar target/hdfsWatcher-0.1.0-SNAPSHOT.jar --spring.profiles.active=cloud
   ```

## Configuration

Edit the appropriate properties file:
- `src/main/resources/application-standalone.properties`
- `src/main/resources/application-cloud.properties`
- `src/main/resources/application.properties` (for common settings)

### Key Properties

#### HDFS Mode (default)
- `hdfswatcher.hdfsUri`: HDFS NameNode URI (e.g., `hdfs://localhost:9000`)
- `hdfswatcher.hdfsPath`: Directory to watch (default: `/`)
- `hdfswatcher.pollInterval`: Polling interval in seconds (default: `60`)
- `hdfswatcher.hdfsUser`: HDFS user (default: current system user)
- `hdfswatcher.webhdfs-uri`: WebHDFS URI (optional, e.g., `http://localhost:50070`)

#### Pseudo-operational Mode
To enable local file system mode instead of HDFS:
- `hdfswatcher.pseudoop`: Set to `true` to enable pseudoop mode
- `hdfswatcher.local-storage-path`: Local directory for file storage (default: `/tmp/hdfsWatcher`)

#### Server Configuration
- `server.port`: Web interface port (default: `8080`)
- `spring.servlet.multipart.max-file-size`: Maximum file upload size (default: `512MB`)
- `spring.servlet.multipart.max-request-size`: Maximum request size (default: `512MB`)

## Pseudo-operational Mode

Pseudo-operational (pseudoop) mode allows you to use a local file system instead of HDFS for development and testing purposes.

### Features

- Web-based file upload interface
- Local file storage with configurable directory
- Same JSON output format as HDFS mode
- Automatic file scanning and event generation
- Support for files up to 512MB

### Running in Pseudoop Mode

1. **Using the provided script**:
   ```bash
   ./run-pseudoop.sh
   ```

2. **Or manually**:
   ```bash
   java -jar target/hdfsWatcher.jar \
     --hdfswatcher.pseudoop=true \
     --hdfswatcher.local-storage-path=/path/to/storage \
     --server.port=8080
   ```

3. **Access the web interface** at `http://localhost:8080`

4. **Upload files** using the web interface or by sending a POST request to `http://localhost:8080/` with a multipart file named "file".

## Output Format

```json
{
  "type": "hdfs",
  "url": "webhdfs://localhost:30800/test/yourfile"
}
```

## License

MIT
