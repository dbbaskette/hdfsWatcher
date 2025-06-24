package com.baskettecase.hdfsWatcher;

import com.baskettecase.hdfsWatcher.util.HdfsWatcherConstants;
import com.baskettecase.hdfsWatcher.util.UrlUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Service for managing local file storage operations.
 * Improved with proper logging, validation, and error handling.
 */
@Service
public class LocalFileService {
    
    private static final Logger logger = LoggerFactory.getLogger(LocalFileService.class);
    
    private final Path rootLocation;
    private final HdfsWatcherProperties properties;

    public LocalFileService(HdfsWatcherProperties properties) {
        this.properties = validateProperties(properties);
        this.rootLocation = Paths.get(this.properties.getLocalStoragePath());
        logger.info("LocalFileService initialized with storage path: {}", this.rootLocation);
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(rootLocation);
            
            if (!Files.isDirectory(rootLocation) || !Files.isWritable(rootLocation)) {
                throw new IOException("Storage location is not a writable directory: " + rootLocation);
            }
            
            logger.info("Local storage directory initialized successfully: {}", rootLocation);
        } catch (IOException e) {
            logger.error("Could not initialize storage at: {}", rootLocation, e);
            throw new RuntimeException("Could not initialize storage", e);
        }
    }

    /**
     * Stores a multipart file with proper validation and error handling.
     * 
     * @param file the multipart file to store
     * @return the public URL to access the stored file
     * @throws IllegalArgumentException if file validation fails
     * @throws RuntimeException if storage operation fails
     */
    public String store(MultipartFile file) {
        if (file == null) {
            throw new IllegalArgumentException("File cannot be null");
        }
        
        String originalFilename = StringUtils.cleanPath(file.getOriginalFilename());
        logger.debug("Attempting to store file: {}", originalFilename);
        
        validateFile(file, originalFilename);
        
        try (InputStream inputStream = file.getInputStream()) {
            Files.copy(inputStream, this.rootLocation.resolve(originalFilename),
                StandardCopyOption.REPLACE_EXISTING);
            
            String publicUrl = UrlUtils.buildFileUrl(
                properties.getPublicAppUri(), 
                HdfsWatcherConstants.FILES_PATH, 
                originalFilename
            );
            
            logger.info("File stored successfully: {} -> {}", originalFilename, publicUrl);
            return publicUrl;
            
        } catch (IOException e) {
            logger.error("Failed to store file: {}", originalFilename, e);
            throw new RuntimeException("Failed to store file " + originalFilename, e);
        }
    }
    
    /**
     * Validates properties configuration.
     */
    private HdfsWatcherProperties validateProperties(HdfsWatcherProperties properties) {
        if (properties == null) {
            throw new IllegalArgumentException("HdfsWatcherProperties cannot be null");
        }
        
        String localPath = properties.getLocalStoragePath();
        if (localPath == null || localPath.trim().isEmpty()) {
            throw new IllegalArgumentException("Local storage path cannot be null or empty");
        }
        
        return properties;
    }
    
    /**
     * Validates file before storage.
     */
    private void validateFile(MultipartFile file, String filename) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException(HdfsWatcherConstants.ERROR_EMPTY_FILE + ": " + filename);
        }
        
        if (filename == null || filename.trim().isEmpty()) {
            throw new IllegalArgumentException(HdfsWatcherConstants.ERROR_FILENAME_NULL_EMPTY);
        }
        
        if (filename.contains("..")) {
            throw new IllegalArgumentException(
                HdfsWatcherConstants.ERROR_INVALID_PATH + ": " + filename);
        }
    }

    /**
     * Loads all files in the storage directory.
     * 
     * @return stream of relative paths to all files
     * @throws RuntimeException if directory cannot be read
     */
    public Stream<Path> loadAll() {
        try {
            return Files.walk(this.rootLocation, 1)
                .filter(path -> !path.equals(this.rootLocation))
                .map(this.rootLocation::relativize);
        } catch (IOException e) {
            logger.error("Failed to read stored files from: {}", rootLocation, e);
            throw new RuntimeException("Failed to read stored files", e);
        }
    }

    /**
     * Loads a specific file by filename with validation.
     * 
     * @param filename the name of the file to load
     * @return the resolved path to the file
     * @throws IllegalArgumentException if filename is invalid
     */
    public Path load(String filename) {
        if (filename == null || filename.trim().isEmpty()) {
            throw new IllegalArgumentException(HdfsWatcherConstants.ERROR_FILENAME_NULL_EMPTY);
        }
        
        if (filename.contains("..")) {
            throw new IllegalArgumentException(
                HdfsWatcherConstants.ERROR_INVALID_PATH + ": " + filename);
        }
        
        return rootLocation.resolve(filename);
    }

    /**
     * Loads a file as a Spring Resource with proper validation.
     * 
     * @param filename the name of the file to load
     * @return the file as a Resource
     * @throws IllegalArgumentException if filename is invalid
     * @throws RuntimeException if file cannot be read
     */
    public Resource loadAsResource(String filename) {
        try {
            Path file = load(filename);
            Resource resource = new UrlResource(file.toUri());
            
            if (resource.exists() && resource.isReadable()) {
                logger.debug("Successfully loaded resource: {}", filename);
                return resource;
            } else {
                logger.warn("File not found or not readable: {}", filename);
                throw new RuntimeException("Could not read file: " + filename);
            }
        } catch (MalformedURLException e) {
            logger.error("Malformed URL for file: {}", filename, e);
            throw new RuntimeException("Could not read file: " + filename, e);
        }
    }

    /**
     * Lists all files in the storage directory.
     * 
     * @return list of filenames
     * @throws RuntimeException if directory cannot be read
     */
    public List<String> listFiles() {
        try {
            List<String> files = Files.walk(rootLocation, 1)
                .filter(path -> !path.equals(rootLocation))
                .filter(Files::isRegularFile)
                .map(path -> path.getFileName().toString())
                .collect(Collectors.toList());
            
            logger.debug("Listed {} files from storage directory", files.size());
            return files;
        } catch (IOException e) {
            logger.error("Failed to list files from: {}", rootLocation, e);
            throw new RuntimeException("Failed to list files", e);
        }
    }
}