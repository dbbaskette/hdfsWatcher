package com.baskettecase.hdfsWatcher;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct; // For Spring Boot 3+

import java.util.List;
import java.util.Map;

@Configuration
@ConfigurationProperties(prefix = "hdfswatcher")
public class HdfsWatcherProperties {
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
     * Application mode: 'standalone' or 'scdf'.
     *
     * - 'standalone': outputs webhdfs URLs to terminal
     * - 'scdf': outputs webhdfs URLs to Spring Cloud Data Flow stream (RabbitMQ)
     *
     * This property can be set via --hdfswatcher.mode=standalone or --hdfswatcher.mode=scdf,
     * or via environment variables (HDFSWATCHER_MODE) or profiles (application-scdf.yml).
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

    @Autowired
    private transient Environment environment; // Used to access environment variables

    @PostConstruct
    public void init() {
        // 1. Check for an explicitly set public URI via environment variable
        this.publicAppUri = environment.getProperty("HDFSWATCHER_PUBLIC_APP_URI");

        // 2. If not set, try to derive from VCAP_APPLICATION (Cloud Foundry)
        if (this.publicAppUri == null) {
            String vcapApplicationJson = environment.getProperty("VCAP_APPLICATION");
            if (vcapApplicationJson != null) {
                try {
                    ObjectMapper mapper = new ObjectMapper();
                    Map<String, Object> vcapMap = mapper.readValue(vcapApplicationJson, new TypeReference<Map<String, Object>>() {});
                    @SuppressWarnings("unchecked")
                    List<String> uris = (List<String>) vcapMap.get("application_uris");
                    if (uris != null && !uris.isEmpty()) {
                        // Assuming CF uses HTTPS and takes the first URI
                        this.publicAppUri = "https://" + uris.get(0);
                    }
                } catch (Exception e) {
                    System.err.println("[HdfsWatcherProperties] ERROR parsing VCAP_APPLICATION: " + e.getMessage());
                    // Fall through to default if parsing fails
                }
            }
        }

        // 3. Default to localhost for local development if still not set
        if (this.publicAppUri == null) {
            String serverPort = environment.getProperty("server.port", "8080"); // Get server port
            this.publicAppUri = "http://localhost:" + serverPort;
        }
        System.out.println("[HdfsWatcherProperties] Determined publicAppUri: " + this.publicAppUri);
    }

    // Getter for the publicAppUri
    public String getPublicAppUri() {
        if (publicAppUri == null) {
            // Fallback in case init() hasn't run or environment is not available (e.g., certain test scenarios)
            // This should ideally not be hit in a running CF app.
            String serverPort = environment != null ? environment.getProperty("server.port", "8080") : "8080";
            System.err.println("[HdfsWatcherProperties] WARNING: publicAppUri was not initialized by PostConstruct. Falling back to default http://localhost:" + serverPort);
            return "http://localhost:" + serverPort;
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
}