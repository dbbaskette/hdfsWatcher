# HdfsWatcher

A minimal Spring Boot application for watching an HDFS directory and outputting new file URLs as JSON, either to the terminal (standalone mode) or to a Spring Cloud Stream (RabbitMQ) (scdf mode).

## Features
- Watches a specified HDFS directory for new files
- Outputs WebHDFS URLs as JSON objects
- Two modes:
  - **standalone**: prints JSON to the terminal/logs
  - **scdf**: sends JSON to a message stream (RabbitMQ)
- No embedded web server
- Simple, minimal dependencies

## Usage

### Build
```sh
./mvnw clean package -DskipTests spring-boot:repackage
```

### Run (Standalone)
```sh
java -jar target/hdfsWatcher-0.1.0-SNAPSHOT.jar --spring.profiles.active=standalone
```

### Run (SCDF/Stream)
```sh
java -jar target/hdfsWatcher-0.1.0-SNAPSHOT.jar --spring.profiles.active=scdf
```

Configure HDFS and other settings in the respective `application-standalone.properties` or `application-scdf.properties` files.

## Output Format
Example JSON output:
```json
{"type":"hdfs","url":"webhdfs://localhost:30800/test/yourfile"}
```

## Project Files
- `instructions.txt`: Contains project-specific guidance, requirements, or setup notes, especially for automation or AI-assisted tasks.
- `InitialAppPrompt.txt`: The initial prompt or requirements that guided the automated code generation for this project.
- `versions.txt`: Records the versions of key dependencies and tools used to generate or build this project for reproducibility and reference.

These files are primarily for documentation, reproducibility, and collaboration with automation tools or future maintainers.

## .gitignore
The repository ignores all build outputs (e.g., `target/`), IDE settings (e.g., `.vscode/`), and OS junk files by default.

## License
MIT (or specify your license here)
