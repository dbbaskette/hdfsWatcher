package com.baskettecase.hdfsWatcher;

import com.baskettecase.hdfsWatcher.service.ProcessedFilesService;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import com.baskettecase.hdfsWatcher.service.ProcessingStateService;
import com.baskettecase.hdfsWatcher.util.HdfsWatcherConstants;
import com.baskettecase.hdfsWatcher.util.UrlUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RemoteIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
// removed unused imports
import java.util.stream.Stream;

/**
 * Service responsible for monitoring HDFS or local directories for new files.
 * Improved with proper logging, validation, and resource management.
 */
@Service
public class HdfsWatcherService {
    
    private static final Logger logger = LoggerFactory.getLogger(HdfsWatcherService.class);
    
    private final HdfsWatcherProperties properties;
    private final FileSystem fileSystem;
    private final HdfsWatcherOutput output;
    private final ProcessedFilesService processedFilesService;
    private final ProcessingStateService processingStateService;
    private final boolean pseudoop;
    private final RabbitTemplate rabbitTemplate;
    private final com.baskettecase.hdfsWatcher.monitoring.MonitoringProperties monitoringProperties;
    private final java.nio.file.Path localWatchPath;

    public HdfsWatcherService(HdfsWatcherProperties properties,
                              HdfsWatcherOutput output,
                              ProcessedFilesService processedFilesService,
                              ProcessingStateService processingStateService,
                              MeterRegistry meterRegistry,
                              RabbitTemplate rabbitTemplate,
                              com.baskettecase.hdfsWatcher.monitoring.MonitoringProperties monitoringProperties) throws Exception {
        this.properties = validateProperties(properties);
        this.output = validateOutput(output);
        this.processedFilesService = processedFilesService;
        this.processingStateService = processingStateService;
        this.pseudoop = properties.isPseudoop();
        this.rabbitTemplate = rabbitTemplate;
        this.monitoringProperties = monitoringProperties;
        logger.info("Initializing HdfsWatcherService in {} mode", pseudoop ? "pseudoop" : "HDFS");
        if (this.pseudoop) {
            this.localWatchPath = initializeLocalStorage(properties.getLocalStoragePath());
            this.fileSystem = null;
            logger.info("Local storage initialized at: {}", this.localWatchPath);
        } else {
            this.localWatchPath = null;
            this.fileSystem = initializeHdfsConnection(properties);
            logger.info("HDFS connection initialized for: {}", properties.getHdfsUri());
        }

        // Metrics
        Gauge.builder("hdfswatcher.processing.enabled", () -> this.processingStateService.isProcessingEnabled() ? 1 : 0)
            .description("Processing enabled state (1/0)")
            .register(meterRegistry);
        Gauge.builder("hdfswatcher.last.poll.timestamp", () -> this.lastPollTimestamp)
            .description("Last poll timestamp (epoch millis)")
            .register(meterRegistry);
    }
    
    /**
     * Validates properties configuration.
     */
    private HdfsWatcherProperties validateProperties(HdfsWatcherProperties properties) {
        if (properties == null) {
            throw new IllegalArgumentException("HdfsWatcherProperties cannot be null");
        }
        
        if (properties.getPollInterval() < HdfsWatcherConstants.MIN_POLL_INTERVAL || 
            properties.getPollInterval() > HdfsWatcherConstants.MAX_POLL_INTERVAL) {
            throw new IllegalArgumentException(
                String.format("Poll interval must be between %d and %d seconds", 
                    HdfsWatcherConstants.MIN_POLL_INTERVAL, 
                    HdfsWatcherConstants.MAX_POLL_INTERVAL));
        }
        
        if (!properties.isPseudoop()) {
            if (properties.getHdfsUri() == null || properties.getHdfsUri().trim().isEmpty()) {
                throw new IllegalArgumentException("HDFS URI cannot be null or empty when not in pseudoop mode");
            }
            if (properties.getHdfsPath() == null || properties.getHdfsPath().trim().isEmpty()) {
                throw new IllegalArgumentException("HDFS path cannot be null or empty when not in pseudoop mode");
            }
        }
        
        return properties;
    }
    
    /**
     * Validates output configuration.
     */
    private HdfsWatcherOutput validateOutput(HdfsWatcherOutput output) {
        if (output == null) {
            throw new IllegalArgumentException("HdfsWatcherOutput cannot be null");
        }
        return output;
    }
    
    /**
     * Initializes local storage directory.
     */
    private java.nio.file.Path initializeLocalStorage(String localStoragePath) throws IOException {
        if (localStoragePath == null || localStoragePath.trim().isEmpty()) {
            throw new IllegalArgumentException("Local storage path cannot be null or empty in pseudoop mode");
        }
        
        java.nio.file.Path path = java.nio.file.Paths.get(localStoragePath);
        Files.createDirectories(path);
        
        if (!Files.isDirectory(path) || !Files.isWritable(path)) {
            throw new IOException("Local storage path is not a writable directory: " + localStoragePath);
        }
        
        return path;
    }
    
    /**
     * Initializes HDFS connection with proper configuration.
     */
    private FileSystem initializeHdfsConnection(HdfsWatcherProperties properties) throws Exception {
        Configuration conf = new Configuration();
        conf.set("fs.defaultFS", properties.getHdfsUri());
        
        return FileSystem.get(
            new URI(properties.getHdfsUri()), 
            conf, 
            properties.getHdfsUser()
        );
    }
    
    /**
     * Properly closes HDFS resources when the service is destroyed.
     */
    @PreDestroy
    public void cleanup() {
        if (fileSystem != null) {
            try {
                fileSystem.close();
                logger.info("HDFS FileSystem connection closed successfully");
            } catch (IOException e) {
                logger.error("Error closing HDFS FileSystem", e);
            }
        }
    }

    @Scheduled(fixedDelayString = "${hdfswatcher.pollInterval:60}000")
    public void pollHdfsDirectory() {
        try {
            this.lastPollTimestamp = System.currentTimeMillis();
            if (pseudoop) {
                pollLocalDirectory();
            } else {
                pollHdfs();
            }
        } catch (Exception e) {
            logger.error("Unexpected error during directory polling", e);
        }
    }
    
    /**
     * Polls HDFS directory for new files with proper error handling and duplicate prevention.
     */
    private void pollHdfs() {
        try {
            RemoteIterator<LocatedFileStatus> files = fileSystem.listFiles(
                new Path(properties.getHdfsPath()), 
                false
            );
            int processedCount = 0;
            int skippedCount = 0;
            int batchSize = 0;
            final int MAX_BATCH_SIZE = 5; // Process 5 files at a time
            
            while (files.hasNext()) {
                LocatedFileStatus fileStatus = files.next();
                String filename = fileStatus.getPath().getName();
                long fileSize = fileStatus.getLen();
                long modificationTime = fileStatus.getModificationTime();
                
                // Generate unique hash for the file
                String fileHash = processedFilesService.generateFileHash(filename, fileSize, modificationTime);
                
                // Check if file has already been processed
                if (processedFilesService.isFileProcessed(fileHash)) {
                    skippedCount++;
                    continue;
                }
                
                // Check if processing is enabled before sending to queue
                if (!processingStateService.isProcessingEnabled()) {
                    logger.debug("Processing is disabled, skipping file: {} (hash: {})", filename, fileHash);
                    skippedCount++;
                    continue;
                }
                
                // Process the file - send to queue first, then mark as processed
                try {
                    String webhdfsUrl = buildWebHdfsUrl(fileStatus.getPath());
                    publishFileEvent("FILE_START", fileStatus.getPath().getName());
                    output.send(webhdfsUrl, properties.getMode());
                    publishFileEvent("FILE_COMPLETE", fileStatus.getPath().getName());
                    
                    // Only mark as processed after successful queue send
                    processedFilesService.markFileAsProcessed(fileHash);
                    processedCount++;
                    logger.debug("Successfully processed file: {} (hash: {})", filename, fileHash);
                } catch (Exception e) {
                    logger.error("Failed to process file: {} (hash: {}). Error: {}", 
                        filename, fileHash, e.getMessage());
                    // Don't mark as processed if queue send failed
                    continue;
                }
                
                batchSize++;
                
                // Add a small delay every 5 files to avoid log rate limits
                if (batchSize >= MAX_BATCH_SIZE) {
                    try {
                        Thread.sleep(100); // 100ms delay
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    batchSize = 0;
                }
            }
            
            if (processedCount > 0 || skippedCount > 0) {
                logger.info("HDFS polling completed: {} files processed, {} files skipped", 
                    processedCount, skippedCount);
            }
            
        } catch (IOException e) {
            logger.error("Error polling HDFS directory: {}", properties.getHdfsPath(), e);
        } catch (Exception e) {
            logger.error("Unexpected error during HDFS polling", e);
        }
    }
    
    /**
     * Polls local directory for new files with proper error handling and duplicate prevention.
     */
    private void pollLocalDirectory() {
        try (Stream<java.nio.file.Path> stream = Files.list(localWatchPath)) {
            int processedCount = 0;
            int skippedCount = 0;
            
            for (java.nio.file.Path file : stream.filter(Files::isRegularFile).toList()) {
                String fileName = file.getFileName().toString();
                long fileSize = Files.size(file);
                long modificationTime = Files.getLastModifiedTime(file).toMillis();
                
                // Generate unique hash for the file
                String fileHash = processedFilesService.generateFileHash(fileName, fileSize, modificationTime);
                
                // Check if file has already been processed
                if (processedFilesService.isFileProcessed(fileHash)) {
                    skippedCount++;
                    continue;
                }
                
                // Check if processing is enabled before sending to queue
                if (!processingStateService.isProcessingEnabled()) {
                    logger.debug("Processing is disabled, skipping local file: {} (hash: {})", fileName, fileHash);
                    skippedCount++;
                    continue;
                }
                
                // Process the file - send to queue first, then mark as processed
                try {
                    String fileUrl = UrlUtils.buildFileUrl(
                        properties.getPublicAppUri(), 
                        HdfsWatcherConstants.FILES_PATH, 
                        fileName
                    );
                    publishFileEvent("FILE_START", fileName);
                    output.send(fileUrl, properties.getMode());
                    publishFileEvent("FILE_COMPLETE", fileName);
                    
                    // Only mark as processed after successful queue send
                    processedFilesService.markFileAsProcessed(fileHash);
                    processedCount++;
                    logger.debug("Successfully processed local file: {} (hash: {})", fileName, fileHash);
                } catch (Exception e) {
                    logger.error("Failed to process local file: {} (hash: {}). Error: {}", 
                        fileName, fileHash, e.getMessage());
                    // Don't mark as processed if queue send failed
                    continue;
                }
            }
            
            if (processedCount > 0 || skippedCount > 0) {
                logger.info("Local polling completed: {} files processed, {} files skipped", 
                    processedCount, skippedCount);
            }
        } catch (IOException e) {
            logger.error("Error polling local directory: {}", localWatchPath, e);
        } catch (Exception e) {
            logger.error("Unexpected error during local polling", e);
        }
    }

    private void publishFileEvent(String eventType, String filename) {
        try {
            if (monitoringProperties != null && monitoringProperties.isRabbitmqEnabled()) {
                java.util.Map<String, Object> evt = new java.util.LinkedHashMap<>();

                String runtimeName = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
                String instanceId = (monitoringProperties.getInstanceId() != null && !monitoringProperties.getInstanceId().isBlank())
                    ? monitoringProperties.getInstanceId()
                    : ("hdfsWatcher-" + runtimeName);

                evt.put("instanceId", instanceId);
                evt.put("timestamp", java.time.OffsetDateTime.now().toString());
                evt.put("event", eventType);
                evt.put("status", processingStateService.isProcessingEnabled() ? "PROCESSING" : "IDLE");
                evt.put("hostname", properties.getHostname());
                if (properties.getPublicHostname() != null) {
                    evt.put("publicHostname", properties.getPublicHostname());
                }
                evt.put("filename", filename);

                java.util.Map<String, Object> meta = new java.util.LinkedHashMap<>();
                meta.put("service", "hdfsWatcher");
                meta.put("processingStage", "processing");
                meta.put("bindingState", processingStateService.isProcessingEnabled() ? "running" : "stopped");
                meta.put("inputMode", properties.getMode());
                evt.put("meta", meta);
                String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(evt);
                rabbitTemplate.convertAndSend("", monitoringProperties.getQueueName(), json);
            }
        } catch (Exception ignore) {
        }
    }

    /**
     * Builds WebHDFS URL with proper encoding and validation.
     */
    private String buildWebHdfsUrl(org.apache.hadoop.fs.Path path) {
        if (path == null) {
            throw new IllegalArgumentException("Path cannot be null");
        }
        
        String baseUri = determineBaseUri();
        String encodedPath = buildEncodedPath(path);
        
        String webhdfsUrl = baseUri.replaceAll("/$", "") + 
                           HdfsWatcherConstants.WEBHDFS_PATH + 
                           encodedPath;
        
        return webhdfsUrl;
    }

    private volatile long lastPollTimestamp = 0L;
    
    /**
     * Determines the base URI for WebHDFS operations.
     */
    private String determineBaseUri() {
        String baseUri = properties.getWebhdfsUri();
        
        if (baseUri == null || baseUri.isEmpty()) {
            // Fallback to hdfsUri logic for backward compatibility
            baseUri = buildBaseUriFromHdfsUri();
        }
        
        return baseUri;
    }
    
    /**
     * Builds base URI from HDFS URI for backward compatibility.
     */
    private String buildBaseUriFromHdfsUri() {
        String hdfsUri = properties.getHdfsUri();
        String hostPort = "localhost:9000";
        String scheme = HdfsWatcherConstants.HTTP_SCHEME.replace("://", "");
        
        if (hdfsUri != null && hdfsUri.startsWith(HdfsWatcherConstants.HDFS_SCHEME)) {
            String remainder = hdfsUri.substring(HdfsWatcherConstants.HDFS_SCHEME.length());
            int slashIdx = remainder.indexOf('/');
            hostPort = (slashIdx >= 0) ? remainder.substring(0, slashIdx) : remainder;
        } else if (hdfsUri != null && 
                  (hdfsUri.startsWith(HdfsWatcherConstants.HTTP_SCHEME) || 
                   hdfsUri.startsWith(HdfsWatcherConstants.HTTPS_SCHEME))) {
            // If user already provides http(s) in hdfsUri
            int schemeEnd = hdfsUri.indexOf("://");
            scheme = hdfsUri.substring(0, schemeEnd);
            String remainder = hdfsUri.substring(schemeEnd + 3);
            int slashIdx = remainder.indexOf('/');
            hostPort = (slashIdx >= 0) ? remainder.substring(0, slashIdx) : remainder;
        }
        
        return scheme + "://" + hostPort;
    }
    
    /**
     * Builds properly encoded path from HDFS path.
     */
    private String buildEncodedPath(org.apache.hadoop.fs.Path path) {
        StringBuilder encodedPath = new StringBuilder();
        String[] segments = path.toUri().getPath().split("/");
        
        for (String segment : segments) {
            if (!segment.isEmpty()) {
                try {
                    String encodedSegment = UrlUtils.encodePathSegment(segment);
                    encodedPath.append("/").append(encodedSegment);
                } catch (Exception e) {
                    logger.warn("Failed to encode path segment '{}', using raw segment", segment);
                    encodedPath.append("/").append(segment); // fallback to raw
                }
            }
        }
        
        return encodedPath.toString();
    }
    
    /**
     * Gets the number of processed files.
     * 
     * @return the number of processed files
     */
    public int getProcessedFilesCount() {
        return processedFilesService.getProcessedFilesCount();
    }
    
    /**
     * Clears all processed files tracking.
     * 
     * @return the number of files that were cleared
     */
    public int clearAllProcessedFiles() {
        return processedFilesService.clearAllProcessedFiles();
    }
    
    /**
     * Marks a specific file for reprocessing by its hash.
     * 
     * @param fileHash the file hash to mark for reprocessing
     */
    public void markFileForReprocessing(String fileHash) {
        processedFilesService.markFileForReprocessing(fileHash);
    }
}