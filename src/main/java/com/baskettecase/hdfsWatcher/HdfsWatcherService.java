package com.baskettecase.hdfsWatcher;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.*;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;


import java.io.IOException;
import java.net.URI;
import java.util.HashSet;
import java.util.Set;

@Service
public class HdfsWatcherService {
    private final HdfsWatcherProperties properties;
    private FileSystem fileSystem;
    private final Set<String> seenFiles = new HashSet<>();
    private final HdfsWatcherOutput output;

    public HdfsWatcherService(HdfsWatcherProperties properties, HdfsWatcherOutput output) throws Exception {
        this.properties = properties;
        this.output = output;
        Configuration conf = new Configuration();
        conf.set("fs.defaultFS", properties.getHdfsUri());
        fileSystem = FileSystem.get(new URI(properties.getHdfsUri()), conf, properties.getHdfsUser());
    }

    @Scheduled(fixedDelayString = "${hdfswatcher.pollInterval:60}000")
    public void pollHdfsDirectory() {
        try {
            RemoteIterator<LocatedFileStatus> files = fileSystem.listFiles(new Path(properties.getHdfsPath()), false);
            while (files.hasNext()) {
                LocatedFileStatus fileStatus = files.next();
                String filePath = fileStatus.getPath().toString();
                if (seenFiles.add(filePath)) {
                    String webhdfsUrl = buildWebHdfsUrl(fileStatus.getPath());
                    output.send(webhdfsUrl, properties.getMode());
                }
            }
        } catch (IOException e) {
            System.err.println("Error polling HDFS directory: " + e.getMessage());
        }
    }

    private String buildWebHdfsUrl(Path path) {
        String baseUri = properties.getWebhdfsUri();
        if (baseUri == null || baseUri.isEmpty()) {
            // Fallback to hdfsUri logic for backward compatibility
            String hdfsUri = properties.getHdfsUri();
            String hostPort = "localhost:9000";
            String scheme = "http";
            if (hdfsUri != null && hdfsUri.startsWith("hdfs://")) {
                String remainder = hdfsUri.substring("hdfs://".length());
                int slashIdx = remainder.indexOf('/');
                hostPort = (slashIdx >= 0) ? remainder.substring(0, slashIdx) : remainder;
            } else if (hdfsUri != null && (hdfsUri.startsWith("http://") || hdfsUri.startsWith("https://"))) {
                // If user already provides http(s) in hdfsUri
                int schemeEnd = hdfsUri.indexOf("://");
                scheme = hdfsUri.substring(0, schemeEnd);
                String remainder = hdfsUri.substring(schemeEnd + 3);
                int slashIdx = remainder.indexOf('/');
                hostPort = (slashIdx >= 0) ? remainder.substring(0, slashIdx) : remainder;
            }
            baseUri = scheme + "://" + hostPort;
        }
        // URL-encode each path segment to handle spaces and special characters
        StringBuilder encodedPath = new StringBuilder();
        String[] segments = path.toUri().getPath().split("/");
        for (String segment : segments) {
            if (!segment.isEmpty()) {
                try {
                    // URLEncoder encodes space as +, but in URLs it should be %20
                    encodedPath.append("/").append(java.net.URLEncoder.encode(segment, java.nio.charset.StandardCharsets.UTF_8.toString()).replace("+", "%20"));
                } catch (Exception e) {
                    encodedPath.append("/").append(segment); // fallback to raw
                }
            }
        }
        return baseUri.replaceAll("/$","") + "/webhdfs/v1" + encodedPath.toString();
    }
}
