package com.baskettecase.hdfsWatcher.util;

/**
 * Constants used throughout the HDFS Watcher application.
 * Eliminates magic numbers and hardcoded values.
 */
public final class HdfsWatcherConstants {
    
    private HdfsWatcherConstants() {
        // Utility class - prevent instantiation
    }
    
    // Default values
    public static final int DEFAULT_POLL_INTERVAL_SECONDS = 60;
    public static final int DEFAULT_SERVER_PORT = 8080;
    public static final String DEFAULT_HDFS_PATH = "/";
    public static final String DEFAULT_LOCAL_STORAGE_PATH = "/tmp/hdfsWatcher";
    public static final String DEFAULT_OUTPUT_BINDING = "output";
    public static final String DEFAULT_MAX_FILE_SIZE = "512MB";
    public static final String DEFAULT_MAX_REQUEST_SIZE = "512MB";
    
    // Application modes
    public static final String MODE_STANDALONE = "standalone";
    public static final String MODE_CLOUD = "cloud";
    
    // URL patterns and schemes
    public static final String HDFS_SCHEME = "hdfs://";
    public static final String HTTP_SCHEME = "http://";
    public static final String HTTPS_SCHEME = "https://";
    public static final String WEBHDFS_PATH = "/webhdfs/v1";
    public static final String FILES_PATH = "/files";
    
    // Environment variables
    public static final String ENV_HDFSWATCHER_PSEUDOOP = "HDFSWATCHER_PSEUDOOP";
    public static final String ENV_HDFSWATCHER_PUBLIC_APP_URI = "HDFSWATCHER_PUBLIC_APP_URI";
    public static final String ENV_VCAP_APPLICATION = "VCAP_APPLICATION";
    public static final String ENV_PORT = "PORT";
    public static final String ENV_USER = "USER";
    
    // System properties
    public static final String PROP_HDFSWATCHER_PSEUDOOP = "HDFSWATCHER_PSEUDOOP";
    
    // Spring properties
    public static final String PROP_SERVER_PORT = "server.port";
    public static final String PROP_WEB_APP_TYPE = "spring.main.web-application-type";
    
    // File operation values
    public static final String WEBHDFS_OP_LISTSTATUS = "LISTSTATUS";
    public static final String WEBHDFS_OP_CREATE = "CREATE";
    public static final String WEBHDFS_OP_OPEN = "OPEN";
    public static final String WEBHDFS_OP_GETFILESTATUS = "GETFILESTATUS";
    
    // HTTP status codes
    public static final int HTTP_TEMPORARY_REDIRECT = 307;
    
    // Validation constraints
    public static final int MIN_POLL_INTERVAL = 1;
    public static final int MAX_POLL_INTERVAL = 86400; // 24 hours
    public static final int MIN_PORT = 1;
    public static final int MAX_PORT = 65535;
    
    // Log message prefixes
    public static final String LOG_PREFIX_HDFS_WATCHER = "[HdfsWatcher]";
    public static final String LOG_PREFIX_WEBHDFS_SERVICE = "[WebHdfsService]";
    public static final String LOG_PREFIX_PROPERTIES = "[HdfsWatcherProperties]";
    public static final String LOG_PREFIX_STREAM = "[STREAM]";
    
    // Error messages
    public static final String ERROR_EMPTY_FILE = "Cannot store empty file";
    public static final String ERROR_INVALID_PATH = "Cannot store file with relative path outside current directory";
    public static final String ERROR_WEBHDFS_URI_NOT_SET = "hdfswatcher.webhdfs-uri is not set or empty";
    public static final String ERROR_HDFS_PATH_NOT_SET = "hdfswatcher.hdfs-path is not set or empty";
    public static final String ERROR_FILENAME_NULL_EMPTY = "Filename cannot be null or empty";
}