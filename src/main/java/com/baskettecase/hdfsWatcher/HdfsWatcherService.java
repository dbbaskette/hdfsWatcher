package com.baskettecase.hdfsWatcher;

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
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
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
    private final Set<String> seenFiles = new HashSet<>();
    private final HdfsWatcherOutput output;
    private final boolean pseudoop;
    private final java.nio.file.Path localWatchPath;

    public HdfsWatcherService(HdfsWatcherProperties properties, HdfsWatcherOutput output) throws Exception {
        this.properties = validateProperties(properties);
        this.output = validateOutput(output);
        this.pseudoop = properties.isPseudoop();
        
        logger.info("Initializing HdfsWatcherService in {} mode", 
            pseudoop ? "pseudoop" : "HDFS");
        
        if (this.pseudoop) {
            // In pseudoop mode, use local file system
            this.localWatchPath = initializeLocalStorage(properties.getLocalStoragePath());
            this.fileSystem = null;
            logger.info("Local storage initialized at: {}", this.localWatchPath);
        } else {
            // In HDFS mode
            this.localWatchPath = null;
            this.fileSystem = initializeHdfsConnection(properties);
            logger.info("HDFS connection initialized for: {}", properties.getHdfsUri());
        }
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
        logger.debug("Starting scheduled directory poll in {} mode", pseudoop ? "pseudoop" : "HDFS");
        
        try {
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
     * Polls HDFS directory for new files with proper error handling.
     */
    private void pollHdfs() {
        try {
            RemoteIterator<LocatedFileStatus> files = fileSystem.listFiles(
                new Path(properties.getHdfsPath()), 
                false
            );
            
            int newFilesCount = 0;
            while (files.hasNext()) {
                LocatedFileStatus fileStatus = files.next();
                String filePath = fileStatus.getPath().toString();
                
                if (seenFiles.add(filePath)) {
                    String webhdfsUrl = buildWebHdfsUrl(fileStatus.getPath());
                    output.send(webhdfsUrl, properties.getMode());
                    newFilesCount++;
                    logger.info("New file detected in HDFS: {}", fileStatus.getPath().getName());
                }
            }
            
            if (newFilesCount > 0) {
                logger.info("Processed {} new files from HDFS directory: {}", 
                    newFilesCount, properties.getHdfsPath());
            }
            
        } catch (IOException e) {
            logger.error("Error polling HDFS directory '{}': {}", properties.getHdfsPath(), e.getMessage(), e);
        }
    }
    
    /**
     * Polls local directory for new files with proper error handling and URL encoding.
     */
    private void pollLocalDirectory() {
        try (Stream<java.nio.file.Path> stream = Files.list(localWatchPath)) {
            int newFilesCount = 0;
            
            for (java.nio.file.Path file : stream.filter(Files::isRegularFile).toList()) {
                String filePath = file.toString();
                
                if (seenFiles.add(filePath)) {
                    String fileName = file.getFileName().toString();
                    String fileUrl = UrlUtils.buildFileUrl(
                        properties.getPublicAppUri(), 
                        HdfsWatcherConstants.FILES_PATH, 
                        fileName
                    );
                    
                    output.send(fileUrl, properties.getMode());
                    newFilesCount++;
                    logger.info("New file detected in local directory: {}", fileName);
                }
            }
            
            if (newFilesCount > 0) {
                logger.info("Processed {} new files from local directory: {}", 
                    newFilesCount, localWatchPath);
            }
            
        } catch (IOException e) {
            logger.error("Error polling local directory '{}': {}", localWatchPath, e.getMessage(), e);
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
        
        logger.debug("Built WebHDFS URL: {} for path: {}", webhdfsUrl, path);
        return webhdfsUrl;
    }
    
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
     * Resets the processed files tracking, allowing all files to be reprocessed.
     * This is useful for testing or when you want to reprocess all files.
     */
    public void resetProcessedFiles() {
        int previousCount = seenFiles.size();
        seenFiles.clear();
        logger.info("Reset processed files tracking. Cleared {} previously processed files.", previousCount);
    }
    
    /**
     * Gets the count of currently tracked processed files.
     */
    public int getProcessedFilesCount() {
        return seenFiles.size();
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
}