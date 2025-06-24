package com.baskettecase.hdfsWatcher.service;

import com.baskettecase.hdfsWatcher.HdfsWatcherProperties;
import com.baskettecase.hdfsWatcher.util.HdfsWatcherConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.stereotype.Service;

/**
 * Service responsible for handling complex application bootstrap logic.
 * Extracts and simplifies the main method complexity.
 */
@Service
public class ApplicationBootstrapService {
    
    private static final Logger logger = LoggerFactory.getLogger(ApplicationBootstrapService.class);
    
    /**
     * Bootstraps the application with proper configuration.
     * 
     * @param applicationClass the main application class
     * @param args command line arguments
     * @return the configured application context
     */
    public ConfigurableApplicationContext bootstrap(Class<?> applicationClass, String[] args) {
        logger.info("Starting application bootstrap process");
        
        SpringApplication app = createSpringApplication(applicationClass);
        boolean enableServletEarlyCheck = shouldEnableServletMode(args);
        
        ConfigurableApplicationContext context = app.run(args);
        HdfsWatcherProperties properties = context.getBean(HdfsWatcherProperties.class);
        
        // Restart as web application if needed for pseudoop mode
        if (properties.isPseudoop() && app.getWebApplicationType() != WebApplicationType.SERVLET) {
            logger.info("Restarting application in SERVLET mode for pseudoop support");
            context.close();
            app = createSpringApplication(applicationClass);
            app.setWebApplicationType(WebApplicationType.SERVLET);
            context = app.run(args);
            properties = context.getBean(HdfsWatcherProperties.class);
        }
        
        validateConfiguration(context, properties, enableServletEarlyCheck);
        logApplicationProperties(context, properties);
        
        logger.info("Application bootstrap completed successfully");
        return context;
    }
    
    /**
     * Creates and configures a SpringApplication instance.
     */
    private SpringApplication createSpringApplication(Class<?> applicationClass) {
        SpringApplication app = new SpringApplication(applicationClass);
        app.setBannerMode(Banner.Mode.OFF);
        return app;
    }
    
    /**
     * Determines if servlet mode should be enabled based on various sources.
     */
    private boolean shouldEnableServletMode(String[] args) {
        // Check system property
        if (isPseudoopEnabled(System.getProperty(HdfsWatcherConstants.PROP_HDFSWATCHER_PSEUDOOP))) {
            return true;
        }
        
        // Check environment variable
        if (isPseudoopEnabled(System.getenv(HdfsWatcherConstants.ENV_HDFSWATCHER_PSEUDOOP))) {
            return true;
        }
        
        // Check command line arguments
        return checkCommandLineArgs(args);
    }
    
    /**
     * Checks if pseudoop is enabled from a string value.
     */
    private boolean isPseudoopEnabled(String value) {
        return value != null && (value.equalsIgnoreCase("true") || value.equals("1"));
    }
    
    /**
     * Checks command line arguments for pseudoop configuration.
     */
    private boolean checkCommandLineArgs(String[] args) {
        for (String arg : args) {
            String lowerArg = arg.toLowerCase();
            if (lowerArg.startsWith("--hdfswatcher.pseudoop=") || lowerArg.startsWith("hdfswatcher.pseudoop=")) {
                if (lowerArg.endsWith("=true") || lowerArg.endsWith("=1")) {
                    return true;
                }
            }
            if (lowerArg.equals("--hdfswatcher.pseudoop")) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Validates the application configuration and logs warnings if needed.
     */
    private void validateConfiguration(ConfigurableApplicationContext context, 
                                     HdfsWatcherProperties properties, 
                                     boolean enableServletEarlyCheck) {
        ConfigurableEnvironment environment = (ConfigurableEnvironment) context.getEnvironment();
        String actualWebApplicationTypeStr = environment.getProperty(
            HdfsWatcherConstants.PROP_WEB_APP_TYPE, "NONE");
        WebApplicationType actualType = WebApplicationType.valueOf(actualWebApplicationTypeStr.toUpperCase());
        
        if (properties.isPseudoop() && actualType == WebApplicationType.NONE) {
            logger.warn("HDFS Watcher Pseudoop mode is active but application started as WebApplicationType.NONE");
            logger.warn("This likely means early checks failed to detect pseudoop mode before Spring context initialization");
            logger.warn("The Web UI will likely be unavailable. Check application startup command and environment variable settings");
            logger.warn("Early check for servlet mode resulted in: {}", 
                enableServletEarlyCheck ? "Attempt SERVLET" : "Attempt NONE");
        }
    }
    
    /**
     * Logs comprehensive application properties for debugging.
     */
    private void logApplicationProperties(ConfigurableApplicationContext context, HdfsWatcherProperties properties) {
        logger.info("{} Application Properties:", HdfsWatcherConstants.LOG_PREFIX_HDFS_WATCHER);
        logger.info("  mode          : {}", properties.getMode());
        
        logEnvironmentProperty(context, "hdfsPath", properties.getHdfsPath());
        logEnvironmentProperty(context, "pollInterval", String.valueOf(properties.getPollInterval()));
        logEnvironmentProperty(context, "hdfsUri", properties.getHdfsUri());
        logEnvironmentProperty(context, "hdfsUser", properties.getHdfsUser());
        logEnvironmentProperty(context, "webhdfsUri", properties.getWebhdfsUri());
        
        logger.info("  outputBinding : {}", properties.getOutputBinding());
        
        logOutputDestination(context, properties);
        logPseudoopProperties(context, properties);
    }
    
    /**
     * Logs an environment property with its configured and environment values.
     */
    private void logEnvironmentProperty(ConfigurableApplicationContext context, String propertyName, String value) {
        String envValue = context.getEnvironment().getProperty("hdfswatcher." + propertyName);
        logger.info("  {:<12} : {} [env: {}]", propertyName, value, 
            envValue != null ? envValue : "not set");
    }
    
    /**
     * Logs the output destination configuration.
     */
    private void logOutputDestination(ConfigurableApplicationContext context, HdfsWatcherProperties properties) {
        String bindingName = properties.getOutputBinding();
        String scdfDestKey = "spring.cloud.stream.bindings." + bindingName + ".destination";
        String scdfDestValue = context.getEnvironment().getProperty(scdfDestKey);
        
        if (scdfDestValue == null && bindingName.endsWith("-out-0")) {
            scdfDestKey = "spring.cloud.stream.bindings." + bindingName.replace("-out-0", "") + ".destination";
            scdfDestValue = context.getEnvironment().getProperty(scdfDestKey);
        }
        
        logger.info("  output destination: {}", scdfDestValue != null ? scdfDestValue : "[not set]");
    }
    
    /**
     * Logs pseudoop-specific properties.
     */
    private void logPseudoopProperties(ConfigurableApplicationContext context, HdfsWatcherProperties properties) {
        logger.info("  pseudoop mode   : {}", properties.isPseudoop());
        
        if (properties.isPseudoop()) {
            logger.info("  local storage   : {}", properties.getLocalStoragePath());
            
            ConfigurableEnvironment environment = (ConfigurableEnvironment) context.getEnvironment();
            String actualWebApplicationTypeStr = environment.getProperty(
                HdfsWatcherConstants.PROP_WEB_APP_TYPE, "NONE");
            WebApplicationType actualType = WebApplicationType.valueOf(actualWebApplicationTypeStr.toUpperCase());
            
            if (actualType == WebApplicationType.SERVLET) {
                logger.info("  web interface   : {}/", properties.getPublicAppUri());
            } else {
                logger.info("  web interface   : (Likely unavailable as application is not running as a SERVLET type)");
            }
            
            logger.info("  Effective WebApplicationType: {}", actualType);
        }
    }
}