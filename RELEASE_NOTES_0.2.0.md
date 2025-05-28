# HDFS Watcher v0.2.0 Release Notes

## New Features

### Pseudo-operational Mode
- Added support for local file system operation when HDFS is unavailable
- Web-based file upload interface at `http://localhost:8080`
- Configurable local storage path (default: `/tmp/hdfsWatcher`)
- File upload and download functionality
- Automatic file scanning and event generation

### Web Interface
- Modern, responsive UI with drag-and-drop file upload
- File listing with download links
- Progress indicators for uploads
- Error handling and user feedback

### Configuration
- Added `pseudoop` flag to enable/disable pseudo-operational mode
- Configurable local storage path
- Support for large file uploads (up to 512MB)

## Changes
- Updated Spring Boot to 3.4.5
- Added Spring Web and Thymeleaf dependencies
- Improved error handling and logging

## How to Use

### Running in Pseudoop Mode
```bash
java -jar hdfsWatcher-0.2.0.jar --hdfswatcher.pseudoop=true
```

### Accessing the Web Interface
Open `http://localhost:8080` in your web browser to access the file upload interface.

## Files
- `hdfsWatcher-0.2.0.jar`: The main application JAR file
- `run-pseudoop.sh`: Helper script to run in pseudoop mode

## Requirements
- Java 21 or later
- Maven 3.6 or later (for building from source)

## Known Issues
- None at this time

## Contributors
- @dbbaskette
