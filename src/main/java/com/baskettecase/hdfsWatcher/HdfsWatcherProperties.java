package com.baskettecase.hdfsWatcher;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "hdfswatcher")
public class HdfsWatcherProperties {
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

    public String getOutputBinding() { return outputBinding; }
    public void setOutputBinding(String outputBinding) { this.outputBinding = outputBinding; }

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
