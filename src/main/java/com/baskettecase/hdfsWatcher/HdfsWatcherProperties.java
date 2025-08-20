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
import java.util.Arrays;
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

    /** Paths of the HDFS directories to watch (comma-separated) */
    private String hdfsPathsString = "/";
    private List<String> hdfsPaths = Arrays.asList("/");
    
    /** Test property to verify binding is working */
    private String testProperty = "default";
    
    /** @deprecated Use hdfsPaths instead for multiple directory support */
    @Deprecated
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

    // Host information for monitoring
    private String hostname;        // internal FQDN (canonical)
    private String publicHostname;  // routable host from publicAppUri

    @Autowired
    private transient Environment environment; // Used to access environment variables

    @PostConstruct
    public void init() {
        logger.debug("Initializing HdfsWatcherProperties");
        logger.debug("{} Raw hdfsPathsString: '{}'", HdfsWatcherConstants.LOG_PREFIX_PROPERTIES, hdfsPathsString);
        logger.debug("{} Raw hdfsPath: '{}'", HdfsWatcherConstants.LOG_PREFIX_PROPERTIES, hdfsPath);
        logger.debug("{} Test property: '{}'", HdfsWatcherConstants.LOG_PREFIX_PROPERTIES, testProperty);
        
        // Parse comma-separated hdfsPathsString into hdfsPaths list
        if (hdfsPathsString != null && !hdfsPathsString.trim().isEmpty()) {
            hdfsPaths = Arrays.stream(hdfsPathsString.split(","))
                .map(String::trim)
                .filter(path -> !path.isEmpty())
                .collect(java.util.stream.Collectors.toList());
            logger.info("{} Parsed comma-separated hdfsPaths: {}", 
                HdfsWatcherConstants.LOG_PREFIX_PROPERTIES, hdfsPaths);
        }
        
        // Handle backward compatibility for hdfsPath
        if (hdfsPath != null && !hdfsPath.trim().isEmpty()) {
            // If old hdfsPath is set, use it as the first path
            hdfsPaths = Arrays.asList(hdfsPath.trim());
            logger.info("{} Using legacy hdfsPath: {} (consider migrating to hdfsPaths)", 
                HdfsWatcherConstants.LOG_PREFIX_PROPERTIES, hdfsPath);
        }
        
        // Ensure we have at least one valid path
        if (hdfsPaths.isEmpty()) {
            hdfsPaths = Arrays.asList("/");
            logger.warn("{} No valid HDFS paths configured, defaulting to: {}", 
                HdfsWatcherConstants.LOG_PREFIX_PROPERTIES, hdfsPaths);
        }
        
        logger.info("{} Final HDFS paths to watch: {}", 
            HdfsWatcherConstants.LOG_PREFIX_PROPERTIES, hdfsPaths);
        
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

        // Read version from JAR manifest
        try {
            this.appVersion = getClass().getPackage().getImplementationVersion();
            if (this.appVersion != null) {
                logger.info("{} Loaded app version from JAR manifest: {}", HdfsWatcherConstants.LOG_PREFIX_PROPERTIES, this.appVersion);
            } else {
                logger.warn("{} Implementation-Version not found in JAR manifest, appVersion will be null", HdfsWatcherConstants.LOG_PREFIX_PROPERTIES);
            }
        } catch (Exception e) {
            logger.error("{} Failed to read version from JAR manifest", HdfsWatcherConstants.LOG_PREFIX_PROPERTIES, e);
            this.appVersion = null;
        }

        // Resolve internal hostname (FQDN)
        try {
            this.hostname = java.net.InetAddress.getLocalHost().getCanonicalHostName();
            logger.info("{} Resolved hostname: {}", HdfsWatcherConstants.LOG_PREFIX_PROPERTIES, this.hostname);
        } catch (Exception e) {
            logger.warn("{} Failed to resolve hostname (using 'localhost')", HdfsWatcherConstants.LOG_PREFIX_PROPERTIES, e);
            this.hostname = "localhost";
        }

        // Resolve public hostname from publicAppUri
        try {
            java.net.URI uri = new java.net.URI(this.publicAppUri);
            this.publicHostname = uri.getHost();
            logger.info("{} Resolved publicHostname: {}", HdfsWatcherConstants.LOG_PREFIX_PROPERTIES, this.publicHostname);
        } catch (Exception e) {
            logger.warn("{} Failed to parse publicAppUri for publicHostname", HdfsWatcherConstants.LOG_PREFIX_PROPERTIES, e);
            this.publicHostname = null;
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

    public List<String> getHdfsPaths() { return hdfsPaths; }
    
    public String getHdfsPathsString() { return hdfsPathsString; }
    
    public void setHdfsPathsString(String hdfsPathsString) { 
        this.hdfsPathsString = hdfsPathsString != null ? hdfsPathsString : "/";
    }
    
    public void setHdfsPaths(List<String> hdfsPaths) { 
        this.hdfsPaths = hdfsPaths != null ? hdfsPaths : Arrays.asList("/");
        // Also update the string representation for consistency
        this.hdfsPathsString = String.join(",", hdfsPaths);
    }
    
    /**
     * Setter that can handle both List and String inputs for flexible configuration binding
     */
    public void setHdfsPaths(String hdfsPathsString) {
        this.hdfsPathsString = hdfsPathsString != null ? hdfsPathsString : "/";
        // The actual parsing will happen in @PostConstruct
    }
    
    /** @deprecated Use getHdfsPaths instead for multiple directory support */
    @Deprecated
    public String getHdfsPath() { 
        return hdfsPaths != null && !hdfsPaths.isEmpty() ? hdfsPaths.get(0) : null; 
    }
    
    /** @deprecated Use setHdfsPaths instead for multiple directory support */
    @Deprecated
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

    /** Internal FQDN suitable for logging/monitoring within the runtime environment. */
    public String getHostname() {
        return hostname != null ? hostname : "localhost";
    }

    /** Routable host derived from publicAppUri (e.g., Cloud Foundry route); may be null locally. */
    public String getPublicHostname() {
        if (publicHostname != null) {
            return publicHostname;
        }
        // Best-effort fallback: extract from current publicAppUri if possible
        try {
            java.net.URI uri = new java.net.URI(getPublicAppUri());
            return uri.getHost();
        } catch (Exception e) {
            return null;
        }
    }

    public String getTestProperty() {
        return testProperty;
    }

    public void setTestProperty(String testProperty) {
        this.testProperty = testProperty;
        logger.debug("{} Test property set to: {}", HdfsWatcherConstants.LOG_PREFIX_PROPERTIES, testProperty);
    }
}