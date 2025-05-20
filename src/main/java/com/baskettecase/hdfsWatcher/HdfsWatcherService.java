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
        return properties.getHdfsUri().replaceFirst("hdfs://", "webhdfs://") + path.toUri().getPath();
    }
}
