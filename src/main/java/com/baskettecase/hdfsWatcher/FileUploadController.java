package com.baskettecase.hdfsWatcher;

import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.stream.Collectors;

@Controller
public class FileUploadController {

    private final LocalFileService storageService;
    private final HdfsWatcherProperties properties;
    private final WebHdfsService webHdfsService;
    private final HdfsWatcherOutput output;

    public FileUploadController(LocalFileService storageService, 
                              HdfsWatcherProperties properties,
                              WebHdfsService webHdfsService,
                              HdfsWatcherOutput output) {
        this.storageService = storageService;
        this.properties = properties;
        this.webHdfsService = webHdfsService;
        this.output = output;
    }

    @GetMapping("/")
    public String listUploadedFiles(Model model) {
        List<String> files;
        boolean hdfsDisconnected = false;
        if (properties.isPseudoop()) {
            try {
                files = webHdfsService.listFiles();
            } catch (Exception e) {
                files = List.of();
                hdfsDisconnected = true;
                model.addAttribute("message", "HDFS is disconnected: " + e.getMessage());
            }
        } else {
            files = storageService.loadAll()
                .map(path -> path.getFileName().toString())
                .collect(Collectors.toList());
        }
        model.addAttribute("files", files);
        model.addAttribute("isPseudoop", properties.isPseudoop());
        model.addAttribute("hdfsDisconnected", hdfsDisconnected);
        return "uploadForm";
    }

    @GetMapping("/files/{filename:.+}")
    @ResponseBody
    public ResponseEntity<Resource> serveFile(@PathVariable String filename) {
        String decodedFilename;
        try {
            decodedFilename = java.net.URLDecoder.decode(filename, java.nio.charset.StandardCharsets.UTF_8.toString());
        } catch (Exception e) {
            decodedFilename = filename; // fallback
        }
        Resource file;
        if (properties.isPseudoop()) {
            file = webHdfsService.downloadFile(decodedFilename);
        } else {
            file = storageService.loadAsResource(decodedFilename);
        }
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"" + file.getFilename() + "\"").body(file);
    }

    @PostMapping("/")
    public String handleFileUpload(@RequestParam("file") MultipartFile file, Model model) {
        try {
            String originalFilename = file.getOriginalFilename();
            String publicUrl;
            if (properties.isPseudoop()) {
                webHdfsService.uploadFile(file);
                String baseUrl = properties.getWebhdfsUri();
                String hdfsPath = properties.getHdfsPath();
                String user = properties.getHdfsUser();
                baseUrl = baseUrl.replaceAll("/+$", "");
                if (!hdfsPath.startsWith("/")) hdfsPath = "/" + hdfsPath;
                // Encode only for the URL, not for storage
                String encodedFilename = java.net.URLEncoder.encode(originalFilename, java.nio.charset.StandardCharsets.UTF_8.toString()).replace("+", "%20");
                publicUrl = String.format("%s/webhdfs/v1%s/%s?op=OPEN&user.name=%s", baseUrl, hdfsPath, encodedFilename, user);
            } else {
                storageService.store(file);
                String publicAppUri = properties.getPublicAppUri();
                String encodedFilename = java.net.URLEncoder.encode(originalFilename, java.nio.charset.StandardCharsets.UTF_8.toString()).replace("+", "%20");
                publicUrl = String.format("%s/files/%s", publicAppUri.replaceAll("/+$", ""), encodedFilename);
            }

            // Always send JSON notification to Rabbit/stream
            output.send(publicUrl, properties.getMode());
            model.addAttribute("message", 
                "You successfully uploaded " + file.getOriginalFilename() + "!");
            return "redirect:/";
        } catch (Exception e) {
            model.addAttribute("message", "Failed to upload file: " + file.getOriginalFilename() + ". Error: " + e.getMessage());
            return "redirect:/";
        }
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<?> handleStorageFileNotFound(RuntimeException exc) {
        // Log the exception for server-side tracking
        System.err.println("Handling RuntimeException in FileUploadController: " + exc.getMessage());
        exc.printStackTrace(); // Good for debugging, might be too verbose for prod without specific configuration
        return ResponseEntity.notFound().build();
    }
}