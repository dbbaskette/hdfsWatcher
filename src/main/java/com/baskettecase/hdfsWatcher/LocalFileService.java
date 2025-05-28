package com.baskettecase.hdfsWatcher;

import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class LocalFileService {
    private final Path rootLocation;
    private final HdfsWatcherProperties properties; // Inject properties

    public LocalFileService(HdfsWatcherProperties properties) {
        this.properties = properties; // Store injected properties
        this.rootLocation = Paths.get(properties.getLocalStoragePath());
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(rootLocation);
        } catch (IOException e) {
            throw new RuntimeException("Could not initialize storage", e);
        }
    }

    public String store(MultipartFile file) {
        String originalFilename = StringUtils.cleanPath(file.getOriginalFilename());
        try {
            if (file.isEmpty()) {
                throw new RuntimeException("Failed to store empty file " + originalFilename);
            }
            if (originalFilename.contains("..")) {
                // Security check
                throw new RuntimeException("Cannot store file with relative path outside current directory " + originalFilename);
            }
            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(inputStream, this.rootLocation.resolve(originalFilename),
                    StandardCopyOption.REPLACE_EXISTING);
                
                // URL-encode the filename for use in the path segment
                String encodedFilename = URLEncoder.encode(originalFilename, StandardCharsets.UTF_8.toString());
                
                // Return the public URL to access the file
                return String.format("%s/files/%s", properties.getPublicAppUri(), encodedFilename);
            }
        } catch (UnsupportedEncodingException e) {
            // This should not happen with UTF-8
            throw new RuntimeException("Failed to encode filename " + originalFilename, e);
        } catch (IOException e) {
            throw new RuntimeException("Failed to store file " + originalFilename, e);
        }
    }

    public Stream<Path> loadAll() {
        try {
            return Files.walk(this.rootLocation, 1)
                .filter(path -> !path.equals(this.rootLocation))
                .map(this.rootLocation::relativize);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read stored files", e);
        }
    }

    public Path load(String filename) {
        return rootLocation.resolve(filename);
    }

    public Resource loadAsResource(String filename) {
        try {
            Path file = load(filename);
            Resource resource = new UrlResource(file.toUri());
            if (resource.exists() || resource.isReadable()) {
                return resource;
            } else {
                throw new RuntimeException("Could not read file: " + filename);
            }
        } catch (MalformedURLException e) {
            throw new RuntimeException("Could not read file: " + filename, e);
        }
    }

    public List<String> listFiles() {
        try {
            return Files.walk(rootLocation, 1)
                .filter(path -> !path.equals(rootLocation))
                .map(path -> path.getFileName().toString())
                .collect(Collectors.toList());
        } catch (IOException e) {
            throw new RuntimeException("Failed to list files", e);
        }
    }
}