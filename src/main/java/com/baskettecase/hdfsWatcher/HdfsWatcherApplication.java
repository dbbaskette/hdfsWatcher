package com.baskettecase.hdfsWatcher;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class HdfsWatcherApplication {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(HdfsWatcherApplication.class);
        app.setBannerMode(Banner.Mode.OFF);
        WebApplicationType webAppType = WebApplicationType.NONE;

        // Check if pseudoop mode is enabled to determine WebApplicationType
        // This logic checks environment variables first, then command-line arguments
        boolean isPseudoopEnabled = false;
        String pseudoopEnv = System.getenv("HDFSWATCHER_PSEUDOOP");
        if (pseudoopEnv != null && (pseudoopEnv.equalsIgnoreCase("true") || pseudoopEnv.equals("1"))) {
            isPseudoopEnabled = true;
        } else {
            for (String arg : args) {
                if (arg.startsWith("--hdfswatcher.pseudoop=")) {
                    String value = arg.substring("--hdfswatcher.pseudoop=".length()).toLowerCase();
                    if (value.equals("true") || value.equals("1")) {
                        isPseudoopEnabled = true;
                        break;
                    }
                }
                // Also check for hdfswatcher.pseudoop (Spring Boot property format)
                 if (arg.startsWith("hdfswatcher.pseudoop=")) { // Note: Spring Boot converts this to hdfswatcher.pseudoop internally
                    String value = arg.substring("hdfswatcher.pseudoop=".length()).toLowerCase();
                     if (value.equals("true") || value.equals("1")) {
                        isPseudoopEnabled = true;
                        break;
                    }
                 }
            }
        }
        
        if(isPseudoopEnabled){
            webAppType = WebApplicationType.SERVLET;
        }

        app.setWebApplicationType(webAppType);
        var ctx = app.run(args);
        
        var props = ctx.getBean(com.baskettecase.hdfsWatcher.HdfsWatcherProperties.class);
        System.out.println("[HdfsWatcher] Application Properties:");
        System.out.println("  mode          : " + props.getMode());
        // For each property, print both the configured value and the resolved environment property
        String envHdfsPath = ctx.getEnvironment().getProperty("hdfswatcher.hdfsPath");
        String envPollInterval = ctx.getEnvironment().getProperty("hdfswatcher.pollInterval");
        String envHdfsUri = ctx.getEnvironment().getProperty("hdfswatcher.hdfsUri");
        String envHdfsUser = ctx.getEnvironment().getProperty("hdfswatcher.hdfsUser");
        System.out.println("  hdfsPath      : " + props.getHdfsPath() + " [env: " + (envHdfsPath != null ? envHdfsPath : "not set") + "]");
        System.out.println("  pollInterval  : " + props.getPollInterval() + " [env: " + (envPollInterval != null ? envPollInterval : "not set") + "]");
        System.out.println("  hdfsUri       : " + props.getHdfsUri() + " [env: " + (envHdfsUri != null ? envHdfsUri : "not set") + "]");
        System.out.println("  hdfsUser      : " + props.getHdfsUser() + " [env: " + (envHdfsUser != null ? envHdfsUser : "not set") + "]");
        System.out.println("  outputBinding : " + props.getOutputBinding());
        String envWebhdfsUri = ctx.getEnvironment().getProperty("hdfswatcher.webhdfsUri");
        System.out.println("  webhdfsUri    : " + props.getWebhdfsUri() + " [env: " + (envWebhdfsUri != null ? envWebhdfsUri : "not set") + "]");
        // Print the actual SCDF destination if available
        String bindingName = props.getOutputBinding();
        String scdfDestKey = "spring.cloud.stream.bindings." + bindingName + ".destination"; // Simpler key
        String scdfDestValue = ctx.getEnvironment().getProperty(scdfDestKey);
        if (scdfDestValue == null && bindingName.endsWith("-out-0")) { // Fallback for common pattern
             scdfDestKey = "spring.cloud.stream.bindings." + bindingName.replace("-out-0", "") + ".destination";
             scdfDestValue = ctx.getEnvironment().getProperty(scdfDestKey);
        }
        System.out.println("  output destination: " + (scdfDestValue != null ? scdfDestValue : "[not set]"));
        
        System.out.println("  pseudoop mode   : " + props.isPseudoop());
        if (props.isPseudoop()) { // Use the bean's value for definitive status
             if (webAppType == WebApplicationType.NONE && props.isPseudoop()) { // Check if webapptype was correctly set
                 System.out.println("  [WARNING] Pseudoop mode is active but WebApplicationType was NONE. Web UI might not be available if Spring Boot didn't override or if HDFSWATCHER_PSEUDOOP was not set early enough.");
            }
            System.out.println("  local storage   : " + props.getLocalStoragePath());
            // Use the publicAppUri from HdfsWatcherProperties
            System.out.println("  web interface   : " + props.getPublicAppUri() + "/");
        }
    }
}