package com.baskettecase.hdfsWatcher.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Service to manage the processing state (start/stop) of the HDFS Watcher.
 * Defaults to stopped state to help control demo timing.
 */
@Service
public class ProcessingStateService {
    
    private static final Logger logger = LoggerFactory.getLogger(ProcessingStateService.class);
    
    private volatile boolean isProcessingEnabled = false; // Default to stopped
    
    /**
     * Checks if file processing is currently enabled.
     * 
     * @return true if processing is enabled, false otherwise
     */
    public boolean isProcessingEnabled() {
        return isProcessingEnabled;
    }
    
    /**
     * Enables file processing.
     */
    public void enableProcessing() {
        isProcessingEnabled = true;
        logger.info("File processing has been ENABLED");
    }
    
    /**
     * Disables file processing.
     */
    public void disableProcessing() {
        isProcessingEnabled = false;
        logger.info("File processing has been DISABLED");
    }
    
    /**
     * Toggles the processing state.
     * 
     * @return the new processing state
     */
    public boolean toggleProcessing() {
        isProcessingEnabled = !isProcessingEnabled;
        logger.info("File processing has been {}", isProcessingEnabled ? "ENABLED" : "DISABLED");
        return isProcessingEnabled;
    }
    
    /**
     * Gets the current processing state as a string.
     * 
     * @return "enabled" or "disabled"
     */
    public String getProcessingState() {
        return isProcessingEnabled ? "enabled" : "disabled";
    }
} 