package com.baskettecase.hdfsWatcher;

import com.baskettecase.hdfsWatcher.util.HdfsWatcherConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;

import java.util.List;
import java.util.Map;

/**
 * Configuration properties for HDFS Watcher with proper logging and validation.
 */
@Configuration
@ConfigurationProperties(prefix = "hdfswatcher")
public class HdfsWatcherProperties {
    
    private static final Logger logger = LoggerFactory.getLogger(HdfsWatcherProperties.class);
    /**
     * WebHDFS REST API base URI, e.g. http://hadoop-hdfs-service:9870
     * If set, this will be used to generate WebHDFS URLs instead of hdfsUri.
     */
    private String webhdfsUri;

    /** Path of the HDFS directory to watch */
    private String hdfsPath;
    /** Poll interval in seconds */
    private int pollInterval = 60;
    /** HDFS cluster URI */
    private String hdfsUri;
    /** HDFS user */
    private String hdfsUser;
    /**
     * Application mode: 'standalone' or 'cloud'.
     *
     * - 'standalone': outputs webhdfs URLs to terminal
     * - 'cloud': outputs webhdfs URLs to Spring Cloud Data Flow stream (RabbitMQ)
     *
     * This property can be set via --hdfswatcher.mode=standalone or --hdfswatcher.mode=cloud,
     * or via environment variables (HDFSWATCHER_MODE) or profiles (application-cloud.properties).
     */
    private String mode = "standalone";

    /**
     * The output binding name for stream mode (for StreamBridge).
     * Default is 'output'.
     * Can be overridden via --hdfswatcher.outputBinding=my-binding or env HDFSWATCHER_OUTPUTBINDING.
     */
    private String outputBinding = "output";
    
    /**
     * Enable pseudo-operational mode (local file system instead of HDFS)
     */
    private boolean pseudoop = false;
    
    /**
     * Local storage path for pseudo-operational mode
     */
    private String localStoragePath = "/tmp/hdfsWatcher";

    // Add this field
    private String publicAppUri;

    private String appVersion;

    @Autowired
    private transient Environment environment; // Used to access environment variables

    @PostConstruct
    public void init() {
        logger.debug("Initializing HdfsWatcherProperties");
        
        // 1. Check for an explicitly set public URI via environment variable
        this.publicAppUri = environment.getProperty(HdfsWatcherConstants.ENV_HDFSWATCHER_PUBLIC_APP_URI);

        // 2. If not set, try to derive from VCAP_APPLICATION (Cloud Foundry)
        if (this.publicAppUri == null) {
            this.publicAppUri = determinePublicUriFromVcap();
        }

        // 3. Default to localhost for local development if still not set
        if (this.publicAppUri == null) {
            this.publicAppUri = buildDefaultLocalUri();
        } else {
            // Ensure the hostname part is lowercase
            this.publicAppUri = normalizeHostname(this.publicAppUri);
        }
        
        logger.info("{} Determined publicAppUri: {}", HdfsWatcherConstants.LOG_PREFIX_PROPERTIES, this.publicAppUri);

        // Read version from VERSION file if present
        try {
            java.nio.file.Path versionPath = java.nio.file.Paths.get("VERSION");
            if (java.nio.file.Files.exists(versionPath)) {
                this.appVersion = java.nio.file.Files.readString(versionPath).trim();
                logger.info("{} Loaded app version from VERSION file: {}", HdfsWatcherConstants.LOG_PREFIX_PROPERTIES, this.appVersion);
            } else {
                this.appVersion = null;
                logger.warn("{} VERSION file not found, appVersion will be null", HdfsWatcherConstants.LOG_PREFIX_PROPERTIES);
            }
        } catch (Exception e) {
            logger.error("{} Failed to read VERSION file", HdfsWatcherConstants.LOG_PREFIX_PROPERTIES, e);
            this.appVersion = null;
        }
    }
    
    /**
     * Determines public URI from VCAP_APPLICATION environment variable.
     */
    private String determinePublicUriFromVcap() {
        String vcapApplicationJson = environment.getProperty(HdfsWatcherConstants.ENV_VCAP_APPLICATION);
        if (vcapApplicationJson != null) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                Map<String, Object> vcapMap = mapper.readValue(vcapApplicationJson, new TypeReference<Map<String, Object>>() {});
                @SuppressWarnings("unchecked")
                List<String> uris = (List<String>) vcapMap.get("application_uris");
                if (uris != null && !uris.isEmpty()) {
                    // Assuming CF uses HTTPS and takes the first URI
                    String uri = HdfsWatcherConstants.HTTPS_SCHEME + uris.get(0).toLowerCase();
                    logger.debug("Derived public URI from VCAP_APPLICATION: {}", uri);
                    return uri;
                }
            } catch (Exception e) {
                logger.error("{} ERROR parsing VCAP_APPLICATION", HdfsWatcherConstants.LOG_PREFIX_PROPERTIES, e);
                // Fall through to default if parsing fails
            }
        }
        return null;
    }
    
    /**
     * Builds default local URI for development.
     */
    private String buildDefaultLocalUri() {
        String serverPort = environment.getProperty(HdfsWatcherConstants.PROP_SERVER_PORT, 
            String.valueOf(HdfsWatcherConstants.DEFAULT_SERVER_PORT));
        String uri = HdfsWatcherConstants.HTTP_SCHEME + "localhost:" + serverPort;
        logger.debug("Using default local URI: {}", uri);
        return uri;
    }
    
    /**
     * Normalizes hostname to lowercase.
     */
    private String normalizeHostname(String uri) {
        try {
            java.net.URI parsedUri = new java.net.URI(uri);
            String host = parsedUri.getHost();
            if (host != null) {
                String newHost = host.toLowerCase();
                if (!host.equals(newHost)) {
                    uri = uri.replace(host, newHost);
                    logger.debug("Normalized hostname in URI: {}", uri);
                }
            }
        } catch (Exception e) {
            logger.warn("{} Could not parse publicAppUri for case normalization: {}", 
                HdfsWatcherConstants.LOG_PREFIX_PROPERTIES, uri, e);
        }
        return uri;
    }

    /**
     * Gets the public application URI with fallback handling.
     * 
     * @return the public application URI
     */
    public String getPublicAppUri() {
        if (publicAppUri == null) {
            // Fallback in case init() hasn't run or environment is not available (e.g., certain test scenarios)
            String serverPort = environment != null ? 
                environment.getProperty(HdfsWatcherConstants.PROP_SERVER_PORT, 
                    String.valueOf(HdfsWatcherConstants.DEFAULT_SERVER_PORT)) : 
                String.valueOf(HdfsWatcherConstants.DEFAULT_SERVER_PORT);
                
            String fallbackUri = HdfsWatcherConstants.HTTP_SCHEME + "localhost:" + serverPort;
            logger.warn("{} publicAppUri was not initialized by PostConstruct. Falling back to default: {}", 
                HdfsWatcherConstants.LOG_PREFIX_PROPERTIES, fallbackUri);
            return fallbackUri;
        }
        return publicAppUri;
    }

    public String getOutputBinding() { return outputBinding; }
    
    public boolean isPseudoop() { return pseudoop; }
    public void setPseudoop(boolean pseudoop) { this.pseudoop = pseudoop; }
    
    public String getLocalStoragePath() { return localStoragePath; }
    public void setLocalStoragePath(String localStoragePath) { this.localStoragePath = localStoragePath; }
    public void setOutputBinding(String outputBinding) { this.outputBinding = outputBinding; }

    public String getWebhdfsUri() { return webhdfsUri; }
    public void setWebhdfsUri(String webhdfsUri) { this.webhdfsUri = webhdfsUri; }

    public String getHdfsPath() { return hdfsPath; }
    public void setHdfsPath(String hdfsPath) { this.hdfsPath = hdfsPath; }
    public int getPollInterval() { return pollInterval; }
    public void setPollInterval(int pollInterval) { this.pollInterval = pollInterval; }
    public String getHdfsUri() { return hdfsUri; }
    public void setHdfsUri(String hdfsUri) { this.hdfsUri = hdfsUri; }
    public String getHdfsUser() { return hdfsUser; }
    public void setHdfsUser(String hdfsUser) { this.hdfsUser = hdfsUser; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public String getAppVersion() {
        return appVersion;
    }
}