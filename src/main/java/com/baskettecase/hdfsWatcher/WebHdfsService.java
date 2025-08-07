package com.baskettecase.hdfsWatcher;

import com.baskettecase.hdfsWatcher.util.HdfsWatcherConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service for WebHDFS operations with proper logging and validation.
 */
@Service
public class WebHdfsService implements HealthIndicator {
    
    private static final Logger logger = LoggerFactory.getLogger(WebHdfsService.class);
    
    private final HdfsWatcherProperties properties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WebHdfsService(HdfsWatcherProperties properties) {
        this.properties = validateProperties(properties);
        this.restTemplate = new RestTemplate();
        logger.info("WebHdfsService initialized with WebHDFS URI: {}", 
            this.properties.getWebhdfsUri());
    }

    /**
     * Lists files from WebHDFS with proper validation and error handling.
     * 
     * @return list of filenames
     * @throws IllegalStateException if configuration is invalid
     * @throws RuntimeException if WebHDFS operation fails
     */
    public List<String> listFiles() {
        validateConfiguration();
        
        String baseUrl = properties.getWebhdfsUri();
        String hdfsPath = properties.getHdfsPath();
        String user = properties.getHdfsUser();
        
        // Normalize URLs
        baseUrl = baseUrl.replaceAll("/+$", "");
        if (!hdfsPath.startsWith("/")) {
            hdfsPath = "/" + hdfsPath;
        }
        
        String url = String.format("%s%s%s?op=%s&user.name=%s", 
            baseUrl, 
            HdfsWatcherConstants.WEBHDFS_PATH,
            hdfsPath, 
            HdfsWatcherConstants.WEBHDFS_OP_LISTSTATUS, 
            user);
        
        logger.debug("{} Listing files from URL: {}", HdfsWatcherConstants.LOG_PREFIX_WEBHDFS_SERVICE, url);
        
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            
            if (response.getStatusCode().is2xxSuccessful()) {
                List<String> files = parseFileListResponse(response.getBody());
                logger.info("Successfully listed {} files from WebHDFS path: {}", files.size(), hdfsPath);
                return files;
            } else {
                logger.error("WebHDFS LISTSTATUS failed with status: {}", response.getStatusCode());
                throw new RuntimeException("WebHDFS LISTSTATUS failed: " + response.getStatusCode());
            }
        } catch (Exception e) {
            logger.error("Failed to list files from WebHDFS path: {}", hdfsPath, e);
            throw new RuntimeException("Failed to list files from WebHDFS", e);
        }
    }

    /**
     * Lists files with detailed metadata from WebHDFS.
     * 
     * @return list of file details with metadata
     * @throws IllegalStateException if configuration is invalid
     * @throws RuntimeException if WebHDFS operation fails
     */
    public List<Map<String, Object>> listFilesWithDetails() {
        validateConfiguration();
        
        String baseUrl = properties.getWebhdfsUri();
        String hdfsPath = properties.getHdfsPath();
        String user = properties.getHdfsUser();
        
        // Normalize URLs
        baseUrl = baseUrl.replaceAll("/+$", "");
        if (!hdfsPath.startsWith("/")) {
            hdfsPath = "/" + hdfsPath;
        }
        
        String url = String.format("%s%s%s?op=%s&user.name=%s", 
            baseUrl, 
            HdfsWatcherConstants.WEBHDFS_PATH,
            hdfsPath, 
            HdfsWatcherConstants.WEBHDFS_OP_LISTSTATUS, 
            user);
        
        logger.debug("{} Listing files with details from URL: {}", HdfsWatcherConstants.LOG_PREFIX_WEBHDFS_SERVICE, url);
        
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            
            if (response.getStatusCode().is2xxSuccessful()) {
                List<Map<String, Object>> fileDetails = parseFileListWithDetailsResponse(response.getBody());
                logger.info("Successfully listed {} files with details from WebHDFS path: {}", fileDetails.size(), hdfsPath);
                return fileDetails;
            } else {
                logger.error("WebHDFS LISTSTATUS failed with status: {}", response.getStatusCode());
                throw new RuntimeException("WebHDFS LISTSTATUS failed: " + response.getStatusCode());
            }
        } catch (Exception e) {
            logger.error("Failed to list files with details from WebHDFS path: {}", hdfsPath, e);
            throw new RuntimeException("Failed to list files with details from WebHDFS", e);
        }
    }

    /**
     * Gets detailed information for a specific file.
     * 
     * @param filename the name of the file
     * @return file details with metadata
     * @throws IllegalArgumentException if filename is invalid
     * @throws RuntimeException if WebHDFS operation fails
     */
    public Map<String, Object> getFileDetails(String filename) {
        validateConfiguration();
        validateDownloadFilename(filename);
        
        String baseUrl = properties.getWebhdfsUri();
        String hdfsPath = properties.getHdfsPath();
        String user = properties.getHdfsUser();
        
        // Normalize paths
        baseUrl = baseUrl.replaceAll("/+$", "");
        if (!hdfsPath.startsWith("/")) {
            hdfsPath = "/" + hdfsPath;
        }
        
        String url = String.format("%s%s%s/%s?op=%s&user.name=%s", 
            baseUrl, 
            HdfsWatcherConstants.WEBHDFS_PATH,
            hdfsPath, 
            filename, 
            HdfsWatcherConstants.WEBHDFS_OP_GETFILESTATUS, 
            user);
        
        logger.debug("{} Getting file details for: {} from URL: {}", 
            HdfsWatcherConstants.LOG_PREFIX_WEBHDFS_SERVICE, filename, url);
        
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            
            if (response.getStatusCode().is2xxSuccessful()) {
                Map<String, Object> fileDetails = parseFileStatusResponse(response.getBody(), filename);
                logger.debug("Successfully retrieved file details for: {}", filename);
                return fileDetails;
            } else {
                logger.error("WebHDFS GETFILESTATUS failed for file '{}' with status: {}", filename, response.getStatusCode());
                throw new RuntimeException("WebHDFS GETFILESTATUS failed: " + response.getStatusCode());
            }
        } catch (Exception e) {
            logger.error("Failed to get file details for '{}' from WebHDFS", filename, e);
            throw new RuntimeException("Failed to get file details from WebHDFS", e);
        }
    }
    
    /**
     * Validates properties configuration.
     */
    private HdfsWatcherProperties validateProperties(HdfsWatcherProperties properties) {
        if (properties == null) {
            throw new IllegalArgumentException("HdfsWatcherProperties cannot be null");
        }
        return properties;
    }
    
    /**
     * Validates WebHDFS configuration before operations.
     */
    private void validateConfiguration() {
        if (properties.getWebhdfsUri() == null || properties.getWebhdfsUri().isEmpty()) {
            throw new IllegalStateException(HdfsWatcherConstants.ERROR_WEBHDFS_URI_NOT_SET);
        }
        if (properties.getHdfsPath() == null || properties.getHdfsPath().isEmpty()) {
            throw new IllegalStateException(HdfsWatcherConstants.ERROR_HDFS_PATH_NOT_SET);
        }
    }
    
    /**
     * Parses the WebHDFS file list response.
     */
    private List<String> parseFileListResponse(String responseBody) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        List<String> files = new ArrayList<>();
        
        JsonNode fileStatuses = root.path("FileStatuses").path("FileStatus");
        for (JsonNode fileNode : fileStatuses) {
            String filename = fileNode.path("pathSuffix").asText();
            if (filename != null && !filename.isEmpty()) {
                files.add(filename);
            }
        }
        
        return files;
    }

    /**
     * Parses the WebHDFS file list response with detailed metadata.
     */
    private List<Map<String, Object>> parseFileListWithDetailsResponse(String responseBody) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        List<Map<String, Object>> fileDetails = new ArrayList<>();
        
        JsonNode fileStatuses = root.path("FileStatuses").path("FileStatus");
        for (JsonNode fileNode : fileStatuses) {
            Map<String, Object> fileInfo = new HashMap<>();
            
            String filename = fileNode.path("pathSuffix").asText();
            if (filename != null && !filename.isEmpty()) {
                fileInfo.put("filename", filename);
                fileInfo.put("size", fileNode.path("length").asLong());
                fileInfo.put("modificationTime", fileNode.path("modificationTime").asLong());
                fileInfo.put("permission", fileNode.path("permission").asText());
                fileInfo.put("owner", fileNode.path("owner").asText());
                fileInfo.put("group", fileNode.path("group").asText());
                fileInfo.put("type", fileNode.path("type").asText());
                
                fileDetails.add(fileInfo);
            }
        }
        
        return fileDetails;
    }

    /**
     * Parses the WebHDFS file status response for a single file.
     */
    private Map<String, Object> parseFileStatusResponse(String responseBody, String filename) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode fileStatus = root.path("FileStatus");
        
        Map<String, Object> fileInfo = new HashMap<>();
        fileInfo.put("filename", filename);
        fileInfo.put("size", fileStatus.path("length").asLong());
        fileInfo.put("modificationTime", fileStatus.path("modificationTime").asLong());
        fileInfo.put("permission", fileStatus.path("permission").asText());
        fileInfo.put("owner", fileStatus.path("owner").asText());
        fileInfo.put("group", fileStatus.path("group").asText());
        fileInfo.put("type", fileStatus.path("type").asText());
        
        return fileInfo;
    }

    /**
     * Uploads a file to WebHDFS with proper validation and error handling.
     * 
     * @param file the multipart file to upload
     * @throws IllegalArgumentException if file is invalid
     * @throws RuntimeException if upload operation fails
     */
    public void uploadFile(MultipartFile file) {
        validateConfiguration();
        validateUploadFile(file);
        
        String baseUrl = properties.getWebhdfsUri();
        String hdfsPath = properties.getHdfsPath();
        String user = properties.getHdfsUser();
        String filename = file.getOriginalFilename();
        
        // Normalize paths
        baseUrl = baseUrl.replaceAll("/+$", "");
        if (!hdfsPath.startsWith("/")) {
            hdfsPath = "/" + hdfsPath;
        }
        
        String url = String.format("%s%s%s/%s?op=%s&overwrite=true&user.name=%s", 
            baseUrl, 
            HdfsWatcherConstants.WEBHDFS_PATH,
            hdfsPath, 
            filename, 
            HdfsWatcherConstants.WEBHDFS_OP_CREATE, 
            user);
        
        logger.info("Uploading file '{}' to WebHDFS path: {}", filename, hdfsPath);
        
        try {
            // 1. Initiate file creation (WebHDFS expects a 307 redirect)
            String location = initiateFileCreation(url);
            
            // 2. Upload file data to redirected location
            uploadFileData(file, location);
            
            logger.info("Successfully uploaded file '{}' to WebHDFS", filename);
            
        } catch (IOException e) {
            logger.error("Failed to read file '{}' for upload", filename, e);
            throw new RuntimeException("Failed to read file for upload", e);
        } catch (Exception e) {
            logger.error("Failed to upload file '{}' to WebHDFS", filename, e);
            throw new RuntimeException("Failed to upload file to WebHDFS", e);
        }
    }
    
    /**
     * Validates file for upload.
     */
    private void validateUploadFile(MultipartFile file) {
        if (file == null) {
            throw new IllegalArgumentException("File cannot be null");
        }
        if (file.isEmpty()) {
            throw new IllegalArgumentException(HdfsWatcherConstants.ERROR_EMPTY_FILE);
        }
        if (file.getOriginalFilename() == null || file.getOriginalFilename().trim().isEmpty()) {
            throw new IllegalArgumentException(HdfsWatcherConstants.ERROR_FILENAME_NULL_EMPTY);
        }
    }
    
    /**
     * Initiates file creation and returns the redirect location.
     */
    private String initiateFileCreation(String url) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        HttpEntity<byte[]> requestEntity = new HttpEntity<>(new byte[0], headers);
        
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.PUT, requestEntity, String.class);
        
        if (response.getStatusCode().value() != HdfsWatcherConstants.HTTP_TEMPORARY_REDIRECT) {
            logger.error("WebHDFS CREATE did not return 307 redirect, got: {}", response.getStatusCode());
            throw new RuntimeException("WebHDFS CREATE did not return 307 redirect");
        }
        
        String location = response.getHeaders().getLocation().toString();
        logger.debug("Received redirect location: {}", location);
        return location;
    }
    
    /**
     * Uploads file data to the redirected location.
     */
    private void uploadFileData(MultipartFile file, String location) throws IOException {
        InputStreamResource resource = new InputStreamResource(file.getInputStream());
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        HttpEntity<InputStreamResource> fileEntity = new HttpEntity<>(resource, fileHeaders);
        
        ResponseEntity<String> uploadResp = restTemplate.exchange(location, HttpMethod.PUT, fileEntity, String.class);
        
        if (!uploadResp.getStatusCode().is2xxSuccessful()) {
            logger.error("WebHDFS file upload failed with status: {}", uploadResp.getStatusCode());
            throw new RuntimeException("WebHDFS file upload failed: " + uploadResp.getStatusCode());
        }
    }

    /**
     * Downloads a file from WebHDFS with proper validation and error handling.
     * 
     * @param filename the name of the file to download
     * @return the file as a Resource
     * @throws IllegalArgumentException if filename is invalid
     * @throws IllegalStateException if configuration is invalid
     * @throws RuntimeException if download operation fails
     */
    public Resource downloadFile(String filename) {
        validateConfiguration();
        validateDownloadFilename(filename);
        
        String baseUrl = properties.getWebhdfsUri();
        String hdfsPath = properties.getHdfsPath();
        String user = properties.getHdfsUser();
        
        // Normalize paths
        baseUrl = baseUrl.replaceAll("/+$", "");
        if (!hdfsPath.startsWith("/")) {
            hdfsPath = "/" + hdfsPath;
        }
        
        String url = String.format("%s%s%s/%s?op=%s&user.name=%s", 
            baseUrl, 
            HdfsWatcherConstants.WEBHDFS_PATH,
            hdfsPath, 
            filename, 
            HdfsWatcherConstants.WEBHDFS_OP_OPEN, 
            user);
        
        logger.info("Downloading file '{}' from WebHDFS path: {}", filename, hdfsPath);
        
        try {
            ResponseEntity<byte[]> response = restTemplate.exchange(url, HttpMethod.GET, null, byte[].class);
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                logger.info("Successfully downloaded file '{}' ({} bytes)", 
                    filename, response.getBody().length);
                
                return new org.springframework.core.io.ByteArrayResource(response.getBody()) {
                    @Override
                    public String getFilename() {
                        return filename;
                    }
                };
            } else {
                logger.error("WebHDFS OPEN failed for file '{}' with status: {}", filename, response.getStatusCode());
                throw new RuntimeException("WebHDFS OPEN failed: " + response.getStatusCode());
            }
        } catch (Exception e) {
            logger.error("Failed to download file '{}' from WebHDFS", filename, e);
            throw new RuntimeException("Failed to download file from WebHDFS", e);
        }
    }
    
    /**
     * Validates filename for download.
     */
    private void validateDownloadFilename(String filename) {
        if (filename == null || filename.trim().isEmpty()) {
            throw new IllegalArgumentException(HdfsWatcherConstants.ERROR_FILENAME_NULL_EMPTY);
        }
        if (filename.contains("..")) {
            throw new IllegalArgumentException(
                HdfsWatcherConstants.ERROR_INVALID_PATH + ": " + filename);
        }
    }

    @Override
    public Health health() {
        try {
            // When pseudoop, report UP with detail
            if (properties.isPseudoop()) {
                return Health.up().withDetail("mode", "pseudoop").build();
            }
            // Attempt a lightweight LISTSTATUS to check connectivity
            validateConfiguration();
            String baseUrl = properties.getWebhdfsUri();
            String hdfsPath = properties.getHdfsPath();
            String user = properties.getHdfsUser();
            baseUrl = baseUrl.replaceAll("/+$", "");
            if (!hdfsPath.startsWith("/")) {
                hdfsPath = "/" + hdfsPath;
            }
            String url = String.format("%s%s%s?op=%s&user.name=%s",
                baseUrl, HdfsWatcherConstants.WEBHDFS_PATH, hdfsPath, HdfsWatcherConstants.WEBHDFS_OP_LISTSTATUS, user);
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            if (response.getStatusCode().is2xxSuccessful()) {
                return Health.up().withDetail("webhdfs", "reachable").build();
            }
            return Health.down().withDetail("webhdfs", "non-2xx").build();
        } catch (Exception e) {
            return Health.down(e).build();
        }
    }
}
