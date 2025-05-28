package com.baskettecase.hdfsWatcher;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RemoteIterator;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
// Paths used via fully qualified name
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

@Service
public class HdfsWatcherService {
    private final HdfsWatcherProperties properties;
    private final FileSystem fileSystem;
    private final Set<String> seenFiles = new HashSet<>();
    private final HdfsWatcherOutput output;
    private final boolean pseudoop;
    private final java.nio.file.Path localWatchPath;

    public HdfsWatcherService(HdfsWatcherProperties properties, HdfsWatcherOutput output) throws Exception {
        this.properties = properties;
        this.output = output;
        this.pseudoop = properties.isPseudoop();
        
        if (this.pseudoop) {
            // In pseudoop mode, use local file system
            this.localWatchPath = java.nio.file.Paths.get(properties.getLocalStoragePath());
            Files.createDirectories(localWatchPath);
            this.fileSystem = null;
        } else {
            // In HDFS mode
            this.localWatchPath = null;
            Configuration conf = new Configuration();
            conf.set("fs.defaultFS", properties.getHdfsUri());
            this.fileSystem = FileSystem.get(
                new URI(properties.getHdfsUri()), 
                conf, 
                properties.getHdfsUser()
            );
        }
    }

    @Scheduled(fixedDelayString = "${hdfswatcher.pollInterval:60}000")
    public void pollHdfsDirectory() {
        if (pseudoop) {
            pollLocalDirectory();
        } else {
            pollHdfs();
        }
    }
    
    private void pollHdfs() {
        try {
            RemoteIterator<LocatedFileStatus> files = fileSystem.listFiles(
                new Path(properties.getHdfsPath()), 
                false
            );
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
    
    private void pollLocalDirectory() {
        try (Stream<java.nio.file.Path> stream = Files.list(localWatchPath)) {
            stream.filter(Files::isRegularFile)
                  .forEach(file -> {
                      String filePath = file.toString();
                      if (seenFiles.add(filePath)) {
                          String fileName = file.getFileName().toString();
                          try {
                              String encodedFileName = URLEncoder.encode(fileName, StandardCharsets.UTF_8.toString());
                              // Use the publicAppUri from HdfsWatcherProperties
                              String fileUrl = String.format("%s/files/%s", 
                                  properties.getPublicAppUri(), 
                                  encodedFileName);
                              output.send(fileUrl, properties.getMode());
                          } catch (UnsupportedEncodingException e) {
                              // This should not happen with UTF-8
                              System.err.println("Error encoding filename for local poll: " + e.getMessage());
                          }
                      }
                  });
        } catch (IOException e) {
            System.err.println("Error polling local directory: " + e.getMessage());
        }
    }

    private String buildWebHdfsUrl(org.apache.hadoop.fs.Path path) {
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