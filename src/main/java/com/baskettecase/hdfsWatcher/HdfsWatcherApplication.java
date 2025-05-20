package com.baskettecase.hdfsWatcher;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class HdfsWatcherApplication {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(HdfsWatcherApplication.class);
        // Disable web server
        app.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
        var ctx = app.run(args);
        // Print out the HDFS directory being watched
        var props = ctx.getBean(com.baskettecase.hdfsWatcher.HdfsWatcherProperties.class);
        System.out.println("[HdfsWatcher] Watching HDFS path: " + props.getHdfsPath() + " on " + props.getHdfsUri() + " as user " + props.getHdfsUser());
    }
}
