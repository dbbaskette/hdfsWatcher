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
        
        // Configure web application type based on profile or properties
        app.setBannerMode(Banner.Mode.OFF);
        
        // Default to no web server
        WebApplicationType webAppType = WebApplicationType.NONE;
        
        // Check if we should enable web server for pseudoop mode
        String pseudoopEnv = System.getenv("HDFSWATCHER_PSEUDOOP");
        if (pseudoopEnv != null && (pseudoopEnv.equalsIgnoreCase("true") || pseudoopEnv.equals("1"))) {
            webAppType = WebApplicationType.SERVLET;
        }
        
        // Check command line args for --hdfswatcher.pseudoop=true
        for (String arg : args) {
            if (arg.startsWith("--hdfswatcher.pseudoop=")) {
                String value = arg.substring("--hdfswatcher.pseudoop=".length()).toLowerCase();
                if (value.equals("true") || value.equals("1")) {
                    webAppType = WebApplicationType.SERVLET;
                    break;
                }
            }
        }
        
        app.setWebApplicationType(webAppType);
        var ctx = app.run(args);
        // Print out all configured properties
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
        String scdfDestKey = "spring.cloud.stream.bindings." + bindingName.replace("-out-0", "") + ".destination";
        String scdfDestValue = ctx.getEnvironment().getProperty(scdfDestKey);
        if (scdfDestValue == null) {
            // Try with the full binding name as fallback
            scdfDestKey = "spring.cloud.stream.bindings." + bindingName + ".destination";
            scdfDestValue = ctx.getEnvironment().getProperty(scdfDestKey);
        }
        System.out.println("  output destination: " + (scdfDestValue != null ? scdfDestValue : "[not set]"));
        
        // Print pseudoop status
        System.out.println("  pseudoop mode   : " + props.isPseudoop());
        if (props.isPseudoop()) {
            System.out.println("  local storage   : " + props.getLocalStoragePath());
            System.out.println("  web interface   : http://localhost:" + 
                ctx.getEnvironment().getProperty("server.port", "8080") + "/"
            );
        }
    }
}
