# HDFS Watcher

A Spring Boot application that monitors an HDFS directory and outputs new file URLs as JSON messages.

## Features

- Monitors HDFS directory for new files
- Outputs WebHDFS URLs as JSON
- Supports two modes:
  - **standalone**: Logs to console
  - **scdf**: Streams to RabbitMQ
- Lightweight with minimal dependencies

## Prerequisites

- Java 11+
- Maven
- Access to HDFS cluster
- (For SCDF mode) RabbitMQ

## Quick Start

1. **Build**
   ```sh
   ./mvnw clean package -DskipTests spring-boot:repackage
   ```

2. **Run in Standalone Mode**
   ```sh
   java -jar target/hdfsWatcher-0.1.0-SNAPSHOT.jar --spring.profiles.active=standalone
   ```

3. **Run in SCDF Mode**
   ```sh
   java -jar target/hdfsWatcher-0.1.0-SNAPSHOT.jar --spring.profiles.active=scdf
   ```

## Configuration

Edit the appropriate properties file:
- `src/main/resources/application-standalone.properties`
- `src/main/resources/application-scdf.properties`

Key properties:
- `hdfswatcher.hdfsUri`: HDFS NameNode URI
- `hdfswatcher.hdfsPath`: Directory to watch
- `hdfswatcher.pollInterval`: Polling interval in seconds
- `hdfswatcher.hdfsUser`: HDFS user

## Output Format

```json
{
  "type": "hdfs",
  "url": "webhdfs://localhost:30800/test/yourfile"
}
```

## License

MIT
