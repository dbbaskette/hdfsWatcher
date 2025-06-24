package com.baskettecase.hdfsWatcher;

import com.baskettecase.hdfsWatcher.service.ApplicationBootstrapService;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Main application class for HDFS Watcher.
 * Simplified with extracted bootstrap logic for better maintainability.
 */
@SpringBootApplication
@EnableScheduling
public class HdfsWatcherApplication {
    
    /**
     * Main entry point for the application.
     * Uses ApplicationBootstrapService to handle complex startup logic.
     * 
     * @param args command line arguments
     */
    public static void main(String[] args) {
        // Use bootstrap service to handle complex startup logic
        ApplicationBootstrapService bootstrapService = new ApplicationBootstrapService();
        ConfigurableApplicationContext context = bootstrapService.bootstrap(HdfsWatcherApplication.class, args);
        
        // Application is now running - context can be used if needed for further operations
        // The bootstrap service handles all the complex configuration and logging
    }
}