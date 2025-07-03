package com.baskettecase.hdfsWatcher;

import com.baskettecase.hdfsWatcher.util.HdfsWatcherConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Component;

/**
 * Service for handling output operations with proper logging and validation.
 */
@Component
public class HdfsWatcherOutput {
    
    private static final Logger logger = LoggerFactory.getLogger(HdfsWatcherOutput.class);
    
    private final StreamBridge streamBridge;
    private final HdfsWatcherProperties properties;

    public HdfsWatcherOutput(StreamBridge streamBridge, HdfsWatcherProperties properties) {
        this.streamBridge = streamBridge;
        this.properties = validateProperties(properties);
        logger.info("HdfsWatcherOutput initialized with StreamBridge and output binding: {}", 
            this.properties.getOutputBinding());
    }

    /**
     * Sends file URL notification with proper validation and logging.
     * 
     * @param webhdfsUrl the file URL to send
     * @param mode the application mode (standalone or cloud)
     * @throws IllegalArgumentException if parameters are invalid
     */
    public void send(String webhdfsUrl, String mode) {
        validateSendParameters(webhdfsUrl, mode);
        
        String json = buildJsonMessage(webhdfsUrl);
        
        if (HdfsWatcherConstants.MODE_CLOUD.equalsIgnoreCase(mode) || 
            "stream".equalsIgnoreCase(mode)) {
            sendToStream(json);
        } else {
            // standalone mode
            sendToConsole(json);
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
     * Validates send parameters.
     */
    private void validateSendParameters(String url, String mode) {
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("URL cannot be null or empty");
        }
        if (mode == null || mode.trim().isEmpty()) {
            throw new IllegalArgumentException("Mode cannot be null or empty");
        }
    }
    
    /**
     * Builds JSON message from URL.
     */
    private String buildJsonMessage(String url) {
        return String.format("{\"type\":\"hdfs\",\"url\":\"%s\"}", url);
    }
    
    /**
     * Sends message to stream for cloud mode.
     */
    private void sendToStream(String json) {
        try {
            String binding = properties.getOutputBinding();
            logger.info("{} {}", HdfsWatcherConstants.LOG_PREFIX_STREAM, json);
            streamBridge.send(binding, json);
            logger.debug("Successfully sent message to binding: {}", binding);
        } catch (Exception e) {
            logger.error("Failed to send message to stream: {}", json, e);
            throw new RuntimeException("Failed to send message to stream", e);
        }
    }
    
    /**
     * Sends message to console for standalone mode.
     */
    private void sendToConsole(String json) {
        logger.info(json);
    }
}
