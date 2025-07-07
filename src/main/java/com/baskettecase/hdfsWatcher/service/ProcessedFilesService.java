package com.baskettecase.hdfsWatcher.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;

/**
 * Service to track processed files to avoid duplicate processing.
 * Uses file hash (filename + size + modification time) for unique identification.
 */
@Service
public class ProcessedFilesService {
    
    private static final Logger logger = LoggerFactory.getLogger(ProcessedFilesService.class);
    
    private final Set<String> processedFiles = new HashSet<>();
    
    /**
     * Generates a unique hash for a file based on its metadata.
     * 
     * @param filename the file name
     * @param fileSize the file size in bytes
     * @param modificationTime the file modification time
     * @return a unique hash string for the file
     */
    public String generateFileHash(String filename, long fileSize, long modificationTime) {
        String fileData = filename + "|" + fileSize + "|" + modificationTime;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(fileData.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            logger.error("SHA-256 algorithm not available, using fallback hash", e);
            // Fallback to simple hash
            return Integer.toString(fileData.hashCode());
        }
    }
    
    /**
     * Checks if a file has been processed.
     * 
     * @param fileHash the file hash to check
     * @return true if the file has been processed, false otherwise
     */
    public boolean isFileProcessed(String fileHash) {
        return processedFiles.contains(fileHash);
    }
    
    /**
     * Marks a file as processed.
     * 
     * @param fileHash the file hash to mark as processed
     */
    public void markFileAsProcessed(String fileHash) {
        processedFiles.add(fileHash);
        logger.debug("Marked file as processed: {}", fileHash);
    }
    
    /**
     * Marks a file for reprocessing by removing it from the processed list.
     * 
     * @param fileHash the file hash to mark for reprocessing
     */
    public void markFileForReprocessing(String fileHash) {
        processedFiles.remove(fileHash);
        logger.debug("Marked file for reprocessing: {}", fileHash);
    }
    
    /**
     * Clears all processed files tracking.
     * 
     * @return the number of files that were cleared
     */
    public int clearAllProcessedFiles() {
        int count = processedFiles.size();
        processedFiles.clear();
        logger.info("Cleared {} processed files from tracking", count);
        return count;
    }
    
    /**
     * Gets the current number of processed files.
     * 
     * @return the number of processed files
     */
    public int getProcessedFilesCount() {
        return processedFiles.size();
    }
    
    /**
     * Gets all processed file hashes (for debugging/admin purposes).
     * 
     * @return a copy of the processed files set
     */
    public Set<String> getAllProcessedFiles() {
        return new HashSet<>(processedFiles);
    }
} 