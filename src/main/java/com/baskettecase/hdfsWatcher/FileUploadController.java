package com.baskettecase.hdfsWatcher;

import com.baskettecase.hdfsWatcher.service.ProcessedFilesService;
import com.baskettecase.hdfsWatcher.service.ProcessingStateService;
import com.baskettecase.hdfsWatcher.util.HdfsWatcherConstants;
import com.baskettecase.hdfsWatcher.util.UrlUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Controller for file upload operations with proper logging and validation.
 */
@Controller
public class FileUploadController {
    
    private static final Logger logger = LoggerFactory.getLogger(FileUploadController.class);

    private final LocalFileService storageService;
    private final HdfsWatcherProperties properties;
    private final WebHdfsService webHdfsService;
    private final HdfsWatcherOutput output;
    private final HdfsWatcherService hdfsWatcherService;
    private final ProcessedFilesService processedFilesService;
    private final ProcessingStateService processingStateService;

    public FileUploadController(LocalFileService storageService, 
                              HdfsWatcherProperties properties,
                              WebHdfsService webHdfsService,
                              HdfsWatcherOutput output,
                              HdfsWatcherService hdfsWatcherService,
                              ProcessedFilesService processedFilesService,
                              ProcessingStateService processingStateService) {
        this.storageService = validateService(storageService, "LocalFileService");
        this.properties = validateService(properties, "HdfsWatcherProperties");
        this.webHdfsService = validateService(webHdfsService, "WebHdfsService");
        this.output = validateService(output, "HdfsWatcherOutput");
        this.hdfsWatcherService = validateService(hdfsWatcherService, "HdfsWatcherService");
        this.processedFilesService = validateService(processedFilesService, "ProcessedFilesService");
        this.processingStateService = validateService(processingStateService, "ProcessingStateService");
        
        String mode = properties.getMode();
        boolean isLocalMode = "standalone".equals(mode) && properties.isPseudoop();
        logger.info("FileUploadController initialized in {} mode", 
            isLocalMode ? "local" : "HDFS");
    }
    
    /**
     * Validates service dependencies.
     */
    private <T> T validateService(T service, String serviceName) {
        if (service == null) {
            throw new IllegalArgumentException(serviceName + " cannot be null");
        }
        return service;
    }

    /**
     * Lists uploaded files with proper error handling and logging.
     * 
     * @param model the Spring model for template rendering
     * @return the template name
     */
    @GetMapping("/")
    public String listUploadedFiles(Model model) {
        String mode = properties.getMode(); // "standalone", "cloud", etc.
        boolean isLocalMode = "standalone".equals(mode) && properties.isPseudoop();
        
        logger.debug("Listing uploaded files in {} mode", isLocalMode ? "local" : "HDFS");
        
        List<String> files;
        boolean hdfsDisconnected = false;
        
        try {
            if (isLocalMode) {
                // Only use local storage in standalone mode with pseudoop=true
                files = storageService.loadAll()
                    .map(path -> path.getFileName().toString())
                    .collect(Collectors.toList());
                logger.debug("Retrieved {} files from local storage", files.size());
            } else {
                // Use WebHDFS for HDFS mode (cloud and standalone with pseudoop=false)
                files = webHdfsService.listFiles();
                logger.debug("Retrieved {} files from WebHDFS", files.size());
            }
        } catch (Exception e) {
            logger.error("Error listing files in {} mode", isLocalMode ? "local" : "HDFS", e);
            files = List.of();
            hdfsDisconnected = !isLocalMode; // HDFS disconnected if not in local mode
            
            String errorMessage = isLocalMode ? 
                "Local storage error: " + e.getMessage() : 
                "HDFS is disconnected: " + e.getMessage();
            model.addAttribute("message", errorMessage);
        }
        
        model.addAttribute("files", files);
        model.addAttribute("isPseudoop", properties.isPseudoop());
        model.addAttribute("isHdfsMode", !isLocalMode);
        model.addAttribute("mode", mode);
        model.addAttribute("hdfsDisconnected", hdfsDisconnected);
        model.addAttribute("appVersion", properties.getAppVersion());
        
        return "uploadForm";
    }

    @GetMapping("/files/{filename:.+}")
    @ResponseBody
    public ResponseEntity<Resource> serveFile(@PathVariable String filename) {
        String decodedFilename;
        try {
            decodedFilename = java.net.URLDecoder.decode(filename, java.nio.charset.StandardCharsets.UTF_8.toString());
        } catch (Exception e) {
            decodedFilename = filename; // fallback
        }
        
        String mode = properties.getMode();
        boolean isLocalMode = "standalone".equals(mode) && properties.isPseudoop();
        
        Resource file;
        if (isLocalMode) {
            file = storageService.loadAsResource(decodedFilename);
        } else {
            file = webHdfsService.downloadFile(decodedFilename);
        }
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"" + file.getFilename() + "\"").body(file);
    }

    /**
     * Handles file upload with proper validation, logging, and URL encoding.
     * 
     * @param file the uploaded file
     * @param model the Spring model for template rendering
     * @return the redirect target
     */
    @PostMapping("/")
    public String handleFileUpload(@RequestParam("file") MultipartFile file, Model model) {
        if (file == null || file.isEmpty()) {
            logger.warn("Attempted to upload null or empty file");
            model.addAttribute("message", "Please select a file to upload");
            return "redirect:/";
        }
        
        String originalFilename = file.getOriginalFilename();
        String mode = properties.getMode();
        boolean isLocalMode = "standalone".equals(mode) && properties.isPseudoop();
        
        logger.info("Handling file upload: {} ({} bytes) in {} mode", 
            originalFilename, file.getSize(), isLocalMode ? "local" : "HDFS");
        
        try {
            String publicUrl = processFileUpload(file, originalFilename);
            
            // Always send JSON notification to Rabbit/stream
            output.send(publicUrl, properties.getMode());
            
            model.addAttribute("message", 
                "You successfully uploaded " + originalFilename + "!");
            
            logger.info("Successfully uploaded file: {} -> {}", originalFilename, publicUrl);
            return "redirect:/";
            
        } catch (Exception e) {
            logger.error("Failed to upload file: {}", originalFilename, e);
            model.addAttribute("message", 
                "Failed to upload file: " + originalFilename + ". Error: " + e.getMessage());
            return "redirect:/";
        }
    }
    
    /**
     * Processes file upload and returns the public URL.
     */
    private String processFileUpload(MultipartFile file, String originalFilename) throws Exception {
        String mode = properties.getMode();
        boolean isLocalMode = "standalone".equals(mode) && properties.isPseudoop();
        
        if (isLocalMode) {
            return processLocalUpload(file);
        } else {
            return processWebHdfsUpload(file, originalFilename);
        }
    }
    
    /**
     * Processes upload to WebHDFS and builds the public URL.
     */
    private String processWebHdfsUpload(MultipartFile file, String originalFilename) throws Exception {
        webHdfsService.uploadFile(file);
        
        String baseUrl = properties.getWebhdfsUri();
        String hdfsPath = properties.getHdfsPath();
        String user = properties.getHdfsUser();
        
        // Normalize paths
        baseUrl = baseUrl.replaceAll("/+$", "");
        if (!hdfsPath.startsWith("/")) {
            hdfsPath = "/" + hdfsPath;
        }
        
        String encodedFilename = UrlUtils.encodeFilename(originalFilename);
        return String.format("%s%s%s/%s?op=%s&user.name=%s", 
            baseUrl, 
            HdfsWatcherConstants.WEBHDFS_PATH,
            hdfsPath, 
            encodedFilename, 
            HdfsWatcherConstants.WEBHDFS_OP_OPEN, 
            user);
    }
    
    /**
     * Processes upload to local storage and builds the public URL.
     */
    private String processLocalUpload(MultipartFile file) {
        return storageService.store(file);
    }

    /**
     * Enhanced status endpoint with comprehensive app status.
     * 
     * @return JSON response with detailed status information
     */
    @GetMapping("/api/status")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getDetailedStatus() {
        try {
            String mode = properties.getMode();
            boolean isLocalMode = "standalone".equals(mode) && properties.isPseudoop();
            
            // Get file listing
            List<String> files = new ArrayList<>();
            boolean hdfsDisconnected = false;
            
            try {
                if (isLocalMode) {
                    files = storageService.loadAll()
                        .map(path -> path.getFileName().toString())
                        .collect(Collectors.toList());
                } else {
                    files = webHdfsService.listFiles();
                }
            } catch (Exception e) {
                logger.error("Error listing files for status", e);
                hdfsDisconnected = !isLocalMode;
            }
            
            // Get processed files info
            int processedCount = processedFilesService.getProcessedFilesCount();
            Set<String> processedHashes = processedFilesService.getAllProcessedFiles();
            
            // Get processing state
            boolean isProcessingEnabled = processingStateService.isProcessingEnabled();
            
            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("mode", mode);
            response.put("isLocalMode", isLocalMode);
            response.put("hdfsDisconnected", hdfsDisconnected);
            response.put("totalFiles", files.size());
            response.put("processedFilesCount", processedCount);
            response.put("processedFilesHashes", processedHashes);
            response.put("processingEnabled", isProcessingEnabled);
            response.put("processingState", processingStateService.getProcessingState());
            response.put("timestamp", System.currentTimeMillis());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error getting detailed status", e);
            Map<String, Object> response = Map.of(
                "status", "error",
                "message", e.getMessage(),
                "timestamp", System.currentTimeMillis()
            );
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * Gets detailed file listing with processing status.
     * 
     * @return JSON response with file details and processing status
     */
    @GetMapping("/api/files")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getFilesWithStatus() {
        try {
            String mode = properties.getMode();
            boolean isLocalMode = "standalone".equals(mode) && properties.isPseudoop();
            
            List<Map<String, Object>> fileDetails = new ArrayList<>();
            boolean hdfsDisconnected = false;
            
            try {
                if (isLocalMode) {
                    // For local mode, we'll create basic file details
                    List<String> files = storageService.loadAll()
                        .map(path -> path.getFileName().toString())
                        .collect(Collectors.toList());
                    
                    for (String filename : files) {
                        Map<String, Object> fileInfo = new HashMap<>();
                        fileInfo.put("filename", filename);
                        fileInfo.put("processed", false); // Default for local mode
                        fileInfo.put("hash", ""); // No hash tracking in local mode
                        fileInfo.put("size", 0L); // No size info in local mode
                        fileInfo.put("modificationTime", 0L); // No time info in local mode
                        fileDetails.add(fileInfo);
                    }
                } else {
                    // Use WebHDFS for detailed file information
                    List<Map<String, Object>> hdfsFiles = webHdfsService.listFilesWithDetails();
                    
                    for (Map<String, Object> hdfsFile : hdfsFiles) {
                        String filename = (String) hdfsFile.get("filename");
                        Long size = (Long) hdfsFile.get("size");
                        Long modificationTime = (Long) hdfsFile.get("modificationTime");
                        
                        // Generate hash for this file
                        String fileHash = processedFilesService.generateFileHash(filename, size, modificationTime);
                        boolean isProcessed = processedFilesService.isFileProcessed(fileHash);
                        
                        Map<String, Object> fileInfo = new HashMap<>(hdfsFile);
                        fileInfo.put("processed", isProcessed);
                        fileInfo.put("hash", fileHash);
                        
                        fileDetails.add(fileInfo);
                    }
                }
                
            } catch (Exception e) {
                logger.error("Error listing files", e);
                hdfsDisconnected = !isLocalMode;
            }
            
            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("files", fileDetails);
            response.put("totalFiles", fileDetails.size());
            response.put("hdfsDisconnected", hdfsDisconnected);
            response.put("mode", mode);
            response.put("processingEnabled", processingStateService.isProcessingEnabled());
            response.put("processingState", processingStateService.getProcessingState());
            response.put("timestamp", System.currentTimeMillis());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error getting files with status", e);
            Map<String, Object> response = Map.of(
                "status", "error",
                "message", e.getMessage(),
                "timestamp", System.currentTimeMillis()
            );
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * Reprocesses specific files by clearing their processed status.
     * 
     * @param request JSON with file hashes to reprocess
     * @return JSON response with reprocessing results
     */
    @PostMapping("/api/reprocess")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> reprocessFiles(@RequestBody Map<String, Object> request) {
        try {
            @SuppressWarnings("unchecked")
            List<String> fileHashes = (List<String>) request.get("fileHashes");
            
            if (fileHashes == null || fileHashes.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "message", "No file hashes provided for reprocessing"
                ));
            }
            
            int reprocessedCount = 0;
            List<String> reprocessedHashes = new ArrayList<>();
            
            for (String hash : fileHashes) {
                if (processedFilesService.isFileProcessed(hash)) {
                    processedFilesService.markFileForReprocessing(hash);
                    reprocessedCount++;
                    reprocessedHashes.add(hash);
                }
            }
            
            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("reprocessedCount", reprocessedCount);
            response.put("reprocessedHashes", reprocessedHashes);
            response.put("message", "Successfully marked " + reprocessedCount + " files for reprocessing");
            response.put("timestamp", System.currentTimeMillis());
            
            logger.info("Reprocessed {} files", reprocessedCount);
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            logger.error("Error reprocessing files", e);
            Map<String, Object> response = Map.of(
                "status", "error",
                "message", "Failed to reprocess files: " + e.getMessage(),
                "timestamp", System.currentTimeMillis()
            );
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * Immediately processes specific files without waiting for the next scan cycle.
     * 
     * @param request JSON with file hashes to process immediately
     * @return JSON response with processing results
     */
    @PostMapping("/api/process-now")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> processFilesNow(@RequestBody Map<String, Object> request) {
        try {
            @SuppressWarnings("unchecked")
            List<String> fileHashes = (List<String>) request.get("fileHashes");
            
            if (fileHashes == null || fileHashes.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "message", "No file hashes provided for processing"
                ));
            }
            
            int processedCount = 0;
            List<String> processedHashes = new ArrayList<>();
            List<String> failedHashes = new ArrayList<>();
            
            String mode = properties.getMode();
            boolean isLocalMode = "standalone".equals(mode) && properties.isPseudoop();
            
            for (String hash : fileHashes) {
                try {
                    // Find the file details by hash
                    String filename = findFilenameByHash(hash);
                    if (filename == null) {
                        logger.warn("Could not find filename for hash: {}", hash);
                        failedHashes.add(hash);
                        continue;
                    }
                    
                    // Process the file immediately
                    String fileUrl = processFileImmediately(filename, isLocalMode);
                    
                    // Send to output (RabbitMQ/stream) first, then mark as processed
                    try {
                        output.send(fileUrl, properties.getMode());
                        
                        // Only mark as processed after successful queue send
                        processedFilesService.markFileAsProcessed(hash);
                        processedCount++;
                        processedHashes.add(hash);
                        
                        logger.info("Immediately processed file: {} -> {}", filename, fileUrl);
                        
                    } catch (Exception e) {
                        logger.error("Failed to send file to queue: {} (hash: {}). Error: {}", 
                            filename, hash, e.getMessage());
                        failedHashes.add(hash);
                        // Don't mark as processed if queue send failed
                        continue;
                    }
                    
                } catch (Exception e) {
                    logger.error("Failed to process file with hash: {}", hash, e);
                    failedHashes.add(hash);
                }
            }
            
            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("processedCount", processedCount);
            response.put("processedHashes", processedHashes);
            response.put("failedHashes", failedHashes);
            response.put("message", "Successfully processed " + processedCount + " files immediately");
            response.put("timestamp", System.currentTimeMillis());
            
            logger.info("Immediately processed {} files", processedCount);
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            logger.error("Error processing files immediately", e);
            Map<String, Object> response = Map.of(
                "status", "error",
                "message", "Failed to process files immediately: " + e.getMessage(),
                "timestamp", System.currentTimeMillis()
            );
            return ResponseEntity.status(500).body(response);
        }
    }
    
    /**
     * Finds filename by hash by looking up in the current file list.
     */
    private String findFilenameByHash(String hash) {
        try {
            if (properties.isPseudoop()) {
                // For local mode, get files from local storage
                List<String> files = storageService.loadAll()
                    .map(path -> path.getFileName().toString())
                    .collect(Collectors.toList());
                
                for (String filename : files) {
                    try {
                        java.nio.file.Path filePath = storageService.load(filename);
                        long fileSize = java.nio.file.Files.size(filePath);
                        long modificationTime = java.nio.file.Files.getLastModifiedTime(filePath).toMillis();
                        String fileHash = processedFilesService.generateFileHash(filename, fileSize, modificationTime);
                        
                        if (hash.equals(fileHash)) {
                            return filename;
                        }
                    } catch (Exception e) {
                        logger.warn("Error checking file hash for: {}", filename, e);
                    }
                }
            } else {
                // For HDFS mode, get files from WebHDFS
                List<Map<String, Object>> hdfsFiles = webHdfsService.listFilesWithDetails();
                
                for (Map<String, Object> hdfsFile : hdfsFiles) {
                    String filename = (String) hdfsFile.get("filename");
                    Long size = (Long) hdfsFile.get("size");
                    Long modificationTime = (Long) hdfsFile.get("modificationTime");
                    
                    String fileHash = processedFilesService.generateFileHash(filename, size, modificationTime);
                    
                    if (hash.equals(fileHash)) {
                        return filename;
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error finding filename by hash: {}", hash, e);
        }
        
        return null;
    }
    
    /**
     * Processes a file immediately and returns the public URL.
     */
    private String processFileImmediately(String filename, boolean isLocalMode) throws Exception {
        if (isLocalMode) {
            // For local mode, build the file URL
            return UrlUtils.buildFileUrl(
                properties.getPublicAppUri(), 
                HdfsWatcherConstants.FILES_PATH, 
                filename
            );
        } else {
            // For HDFS mode, build the WebHDFS URL
            String baseUrl = properties.getWebhdfsUri();
            String hdfsPath = properties.getHdfsPath();
            String user = properties.getHdfsUser();
            
            if (baseUrl == null || baseUrl.isEmpty()) {
                // Fallback to hdfsUri logic
                baseUrl = buildBaseUriFromHdfsUri();
            }
            
            String encodedFilename = UrlUtils.encodePathSegment(filename);
            String webhdfsUrl = baseUrl.replaceAll("/$", "") + 
                               HdfsWatcherConstants.WEBHDFS_PATH + 
                               hdfsPath.replaceAll("/$", "") + "/" + encodedFilename;
            
            return webhdfsUrl;
        }
    }
    
    /**
     * Processes any pending files that haven't been sent to the queue yet.
     * 
     * @return the number of files that were processed
     */
    private int processPendingFiles() {
        int processedCount = 0;
        String mode = properties.getMode();
        boolean isLocalMode = "standalone".equals(mode) && properties.isPseudoop();
        
        try {
            if (isLocalMode) {
                // For local mode, get files from local storage
                List<String> files = storageService.loadAll()
                    .map(path -> path.getFileName().toString())
                    .collect(Collectors.toList());
                
                for (String filename : files) {
                    try {
                        java.nio.file.Path filePath = storageService.load(filename);
                        long fileSize = java.nio.file.Files.size(filePath);
                        long modificationTime = java.nio.file.Files.getLastModifiedTime(filePath).toMillis();
                        String fileHash = processedFilesService.generateFileHash(filename, fileSize, modificationTime);
                        
                        // Check if file has already been processed
                        if (!processedFilesService.isFileProcessed(fileHash)) {
                            // Process the file immediately
                            String fileUrl = UrlUtils.buildFileUrl(
                                properties.getPublicAppUri(), 
                                HdfsWatcherConstants.FILES_PATH, 
                                filename
                            );
                            output.send(fileUrl, properties.getMode());
                            
                            // Mark as processed
                            processedFilesService.markFileAsProcessed(fileHash);
                            processedCount++;
                            logger.info("Immediately processed local file: {} -> {}", filename, fileUrl);
                        }
                    } catch (Exception e) {
                        logger.warn("Error processing local file: {}", filename, e);
                    }
                }
            } else {
                // For HDFS mode, get files from WebHDFS
                List<Map<String, Object>> hdfsFiles = webHdfsService.listFilesWithDetails();
                
                for (Map<String, Object> hdfsFile : hdfsFiles) {
                    String filename = (String) hdfsFile.get("filename");
                    Long size = (Long) hdfsFile.get("size");
                    Long modificationTime = (Long) hdfsFile.get("modificationTime");
                    
                    String fileHash = processedFilesService.generateFileHash(filename, size, modificationTime);
                    
                    // Check if file has already been processed
                    if (!processedFilesService.isFileProcessed(fileHash)) {
                        try {
                            // Process the file immediately
                            String fileUrl = processFileImmediately(filename, false);
                            output.send(fileUrl, properties.getMode());
                            
                            // Mark as processed
                            processedFilesService.markFileAsProcessed(fileHash);
                            processedCount++;
                            logger.info("Immediately processed HDFS file: {} -> {}", filename, fileUrl);
                        } catch (Exception e) {
                            logger.warn("Error processing HDFS file: {}", filename, e);
                        }
                    }
                }
            }
            
            logger.info("Immediately processed {} pending files", processedCount);
            return processedCount;
            
        } catch (Exception e) {
            logger.error("Error processing pending files", e);
            return processedCount; // Return what we processed so far
        }
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
     * Clears all processed files tracking.
     * 
     * @return JSON response with clearing results
     */
    @PostMapping("/api/clear")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> clearAllProcessedFiles() {
        try {
            int clearedCount = processedFilesService.clearAllProcessedFiles();
            
            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("clearedCount", clearedCount);
            response.put("message", "Successfully cleared " + clearedCount + " processed files");
            response.put("timestamp", System.currentTimeMillis());
            
            logger.info("Cleared {} processed files", clearedCount);
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            logger.error("Error clearing processed files", e);
            Map<String, Object> response = Map.of(
                "status", "error",
                "message", "Failed to clear processed files: " + e.getMessage(),
                "timestamp", System.currentTimeMillis()
            );
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * Gets the current status of processed files (legacy endpoint).
     * 
     * @return JSON response with processed files count
     */
    @GetMapping("/status")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getStatus() {
        try {
            int processedCount = hdfsWatcherService.getProcessedFilesCount();
            Map<String, Object> response = Map.of(
                "processedFilesCount", processedCount,
                "status", "success"
            );
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error getting status", e);
            Map<String, Object> response = Map.of(
                "processedFilesCount", 0,
                "status", "error",
                "message", e.getMessage()
            );
            return ResponseEntity.status(500).body(response);
        }
    }
    
    /**
     * Gets the current processing state.
     * 
     * @return JSON response with processing state
     */
    @GetMapping("/api/processing-state")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getProcessingState() {
        try {
            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("processingEnabled", processingStateService.isProcessingEnabled());
            response.put("processingState", processingStateService.getProcessingState());
            response.put("timestamp", System.currentTimeMillis());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error getting processing state", e);
            Map<String, Object> response = Map.of(
                "status", "error",
                "message", e.getMessage(),
                "timestamp", System.currentTimeMillis()
            );
            return ResponseEntity.status(500).body(response);
        }
    }
    
    /**
     * Enables file processing and immediately processes any pending files.
     * 
     * @return JSON response with the new processing state and processing results
     */
    @PostMapping("/api/processing/start")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> startProcessing() {
        try {
            processingStateService.enableProcessing();
            
            // Immediately process any pending files
            int processedCount = processPendingFiles();
            
            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("processingEnabled", true);
            response.put("processingState", "enabled");
            response.put("immediatelyProcessedCount", processedCount);
            response.put("message", "File processing has been ENABLED and " + processedCount + " pending files were processed immediately");
            response.put("timestamp", System.currentTimeMillis());
            
            logger.info("File processing ENABLED via API and {} files processed immediately", processedCount);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error enabling processing", e);
            Map<String, Object> response = Map.of(
                "status", "error",
                "message", "Failed to enable processing: " + e.getMessage(),
                "timestamp", System.currentTimeMillis()
            );
            return ResponseEntity.status(500).body(response);
        }
    }
    
    /**
     * Disables file processing.
     * 
     * @return JSON response with the new processing state
     */
    @PostMapping("/api/processing/stop")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> stopProcessing() {
        try {
            processingStateService.disableProcessing();
            
            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("processingEnabled", false);
            response.put("processingState", "disabled");
            response.put("message", "File processing has been DISABLED");
            response.put("timestamp", System.currentTimeMillis());
            
            logger.info("File processing DISABLED via API");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error disabling processing", e);
            Map<String, Object> response = Map.of(
                "status", "error",
                "message", "Failed to disable processing: " + e.getMessage(),
                "timestamp", System.currentTimeMillis()
            );
            return ResponseEntity.status(500).body(response);
        }
    }
    
    /**
     * Toggles the processing state.
     * 
     * @return JSON response with the new processing state
     */
    @PostMapping("/api/processing/toggle")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> toggleProcessing() {
        try {
            boolean newState = processingStateService.toggleProcessing();
            
            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("processingEnabled", newState);
            response.put("processingState", newState ? "enabled" : "disabled");
            
            // If enabling processing, immediately process pending files
            if (newState) {
                int processedCount = processPendingFiles();
                response.put("immediatelyProcessedCount", processedCount);
                response.put("message", "File processing has been ENABLED and " + processedCount + " pending files were processed immediately");
                logger.info("File processing ENABLED via toggle and {} files processed immediately", processedCount);
            } else {
                response.put("message", "File processing has been DISABLED");
                logger.info("File processing DISABLED via toggle");
            }
            
            response.put("timestamp", System.currentTimeMillis());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error toggling processing", e);
            Map<String, Object> response = Map.of(
                "status", "error",
                "message", "Failed to toggle processing: " + e.getMessage(),
                "timestamp", System.currentTimeMillis()
            );
            return ResponseEntity.status(500).body(response);
        }
    }
    
    /**
     * Clears all processed files tracking (legacy endpoint).
     * 
     * @return JSON response with the number of files cleared
     */
    @PostMapping("/reset")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> resetProcessedFiles() {
        try {
            int clearedCount = hdfsWatcherService.clearAllProcessedFiles();
            Map<String, Object> response = Map.of(
                "success", true,
                "clearedCount", clearedCount,
                "message", "Successfully cleared " + clearedCount + " processed files"
            );
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error resetting processed files", e);
            Map<String, Object> response = Map.of(
                "success", false,
                "message", "Failed to reset processed files: " + e.getMessage()
            );
            return ResponseEntity.status(500).body(response);
        }
    }
    
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<?> handleStorageFileNotFound(RuntimeException exc) {
        logger.error("Handling RuntimeException in FileUploadController", exc);
        return ResponseEntity.notFound().build();
    }
}