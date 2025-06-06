package com.baskettecase.hdfsWatcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Service
public class WebHdfsService {
    private final HdfsWatcherProperties properties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public WebHdfsService(HdfsWatcherProperties properties) {
        this.properties = properties;
        this.restTemplate = new RestTemplate();
    }

    public List<String> listFiles() {
        String baseUrl = properties.getWebhdfsUri();
        String hdfsPath = properties.getHdfsPath();
        String user = properties.getHdfsUser();
        String url = String.format("%s/webhdfs/v1%s?op=LISTSTATUS&user.name=%s", baseUrl, hdfsPath, user);
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            if (response.getStatusCode().is2xxSuccessful()) {
                JsonNode root = objectMapper.readTree(response.getBody());
                List<String> files = new ArrayList<>();
                for (JsonNode fileNode : root.path("FileStatuses").path("FileStatus")) {
                    files.add(fileNode.path("pathSuffix").asText());
                }
                return files;
            } else {
                throw new RuntimeException("WebHDFS LISTSTATUS failed: " + response.getStatusCode());
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to list files from WebHDFS", e);
        }
    }

    public void uploadFile(MultipartFile file) {
        String baseUrl = properties.getWebhdfsUri();
        String hdfsPath = properties.getHdfsPath();
        String user = properties.getHdfsUser();
        String filename = file.getOriginalFilename();
        String url = String.format("%s/webhdfs/v1%s/%s?op=CREATE&overwrite=true&user.name=%s", baseUrl, hdfsPath, filename, user);
        try {
            // 1. Initiate file creation (WebHDFS expects a 307 redirect)
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            HttpEntity<byte[]> requestEntity = new HttpEntity<>(new byte[0], headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.PUT, requestEntity, String.class);
            if (response.getStatusCode() != HttpStatus.TEMPORARY_REDIRECT) {
                throw new RuntimeException("WebHDFS CREATE did not return 307 redirect");
            }
            String location = response.getHeaders().getLocation().toString();

            // 2. Upload file data to redirected location
            HttpEntity<byte[]> fileEntity = new HttpEntity<>(file.getBytes(), headers);
            ResponseEntity<String> uploadResp = restTemplate.exchange(location, HttpMethod.PUT, fileEntity, String.class);
            if (!uploadResp.getStatusCode().is2xxSuccessful()) {
                throw new RuntimeException("WebHDFS file upload failed: " + uploadResp.getStatusCode());
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read file for upload", e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to upload file to WebHDFS", e);
        }
    }
}
