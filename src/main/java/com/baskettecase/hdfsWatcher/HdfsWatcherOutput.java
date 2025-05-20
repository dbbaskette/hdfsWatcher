package com.baskettecase.hdfsWatcher;


import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Component;

@Component
public class HdfsWatcherOutput {
    private final StreamBridge streamBridge;
    private final HdfsWatcherProperties properties;

    public HdfsWatcherOutput(StreamBridge streamBridge, HdfsWatcherProperties properties) {
        this.streamBridge = streamBridge;
        this.properties = properties;
    }

    public void send(String webhdfsUrl, String mode) {
        String json = "{\"type\":\"hdfs\",\"url\":\"" + webhdfsUrl + "\"}";
        if ("stream".equalsIgnoreCase(mode) || "scdf".equalsIgnoreCase(mode)) {
            String binding = properties.getOutputBinding();
            streamBridge.send(binding, json);
        } else {
            System.out.println(json);
        }
    }
}
