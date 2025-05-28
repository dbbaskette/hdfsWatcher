package com.baskettecase.hdfsWatcher;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.core.env.ConfigurableEnvironment; // For actual type checking
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class HdfsWatcherApplication {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(HdfsWatcherApplication.class);
        app.setBannerMode(Banner.Mode.OFF);

        boolean enableServletEarlyCheck = false;

        // 1. Check System Property (e.g., set via -D or JAVA_OPTS)
        String pseudoopSysProp = System.getProperty("HDFSWATCHER_PSEUDOOP");
        if (pseudoopSysProp != null && (pseudoopSysProp.equalsIgnoreCase("true") || pseudoopSysProp.equals("1"))) {
            enableServletEarlyCheck = true;
        }

        // 2. Check Environment Variable (if not found in system properties)
        if (!enableServletEarlyCheck) {
            String pseudoopEnv = System.getenv("HDFSWATCHER_PSEUDOOP");
            if (pseudoopEnv != null && (pseudoopEnv.equalsIgnoreCase("true") || pseudoopEnv.equals("1"))) {
                enableServletEarlyCheck = true;
            }
        }

        // 3. Check Command Line Arguments (if not found in system properties or env vars)
        // This is a simplified check for Spring Boot style args or a standalone flag.
        if (!enableServletEarlyCheck) {
            for (String arg : args) {
                String lowerArg = arg.toLowerCase();
                if (lowerArg.startsWith("--hdfswatcher.pseudoop=") || lowerArg.startsWith("hdfswatcher.pseudoop=")) {
                    if (lowerArg.endsWith("=true") || lowerArg.endsWith("=1")) {
                        enableServletEarlyCheck = true;
                        break;
                    }
                }
                // Check for standalone flag like --hdfswatcher.pseudoop
                if (lowerArg.equals("--hdfswatcher.pseudoop")) {
                    enableServletEarlyCheck = true;
                    break;
                }
            }
        }

        // First run the application to get the context and properties
        var ctx = app.run(args);
        var props = ctx.getBean(com.baskettecase.hdfsWatcher.HdfsWatcherProperties.class);
        
        // If pseudoop is enabled, restart as a web application if needed
        if (props.isPseudoop() && app.getWebApplicationType() != WebApplicationType.SERVLET) {
            System.out.println("Restarting application in SERVLET mode for pseudoop support");
            ctx.close();
            app = new SpringApplication(HdfsWatcherApplication.class);
            app.setBannerMode(Banner.Mode.OFF);
            app.setWebApplicationType(WebApplicationType.SERVLET);
            ctx = app.run(args);
            props = ctx.getBean(com.baskettecase.hdfsWatcher.HdfsWatcherProperties.class);
        }
        
        // Determine the actual web application type Spring Boot used
        ConfigurableEnvironment appEnv = (ConfigurableEnvironment) ctx.getEnvironment();
        String actualWebApplicationTypeStr = appEnv.getProperty("spring.main.web-application-type", "NONE");
        WebApplicationType actualType = WebApplicationType.valueOf(actualWebApplicationTypeStr.toUpperCase());

        // Refined Warning Logic
        if (props.isPseudoop()) { // Pseudoop mode is definitively active based on Spring properties
            if (actualType == WebApplicationType.NONE) {
                System.out.println("  [WARNING] HDFS Watcher Pseudoop mode is active (HdfsWatcherProperties.isPseudoop()=true), " +
                                   "but the application effectively started as WebApplicationType.NONE. ");
                System.out.println("             This likely means the early checks (System Property 'HDFSWATCHER_PSEUDOOP', Environment Variable 'HDFSWATCHER_PSEUDOOP', or command-line args) " +
                                   "failed to detect pseudoop mode before Spring context initialization.");
                System.out.println("             The Web UI will likely be unavailable. Check application startup command and environment variable settings in Cloud Foundry.");
                System.out.println("             Early check for servlet mode resulted in: " + (enableServletEarlyCheck ? "Attempt SERVLET" : "Attempt NONE"));
            }
        }

        System.out.println("[HdfsWatcher] Application Properties:");
        System.out.println("  mode          : " + props.getMode());
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
        String bindingName = props.getOutputBinding();
        String scdfDestKey = "spring.cloud.stream.bindings." + bindingName + ".destination";
        String scdfDestValue = ctx.getEnvironment().getProperty(scdfDestKey);
        if (scdfDestValue == null && bindingName.endsWith("-out-0")) {
             scdfDestKey = "spring.cloud.stream.bindings." + bindingName.replace("-out-0", "") + ".destination";
             scdfDestValue = ctx.getEnvironment().getProperty(scdfDestKey);
        }
        System.out.println("  output destination: " + (scdfDestValue != null ? scdfDestValue : "[not set]"));
        
        System.out.println("  pseudoop mode   : " + props.isPseudoop());
        if (props.isPseudoop()) {
            System.out.println("  local storage   : " + props.getLocalStoragePath());
            if (actualType == WebApplicationType.SERVLET) {
                 System.out.println("  web interface   : " + props.getPublicAppUri() + "/");
            } else {
                 System.out.println("  web interface   : (Likely unavailable as application is not running as a SERVLET type)");
            }
        }
        System.out.println("  Effective WebApplicationType: " + actualType);
    }
}