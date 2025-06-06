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

    public FileUploadController(LocalFileService storageService, 
                              HdfsWatcherProperties properties,
                              WebHdfsService webHdfsService) {
        this.storageService = storageService;
        this.properties = properties;
        this.webHdfsService = webHdfsService;
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
        Resource file;
        if (properties.isPseudoop()) {
            file = webHdfsService.downloadFile(filename);
        } else {
            file = storageService.loadAsResource(filename);
        }
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"" + file.getFilename() + "\"").body(file);
    }

    @PostMapping("/")
    public String handleFileUpload(@RequestParam("file") MultipartFile file, Model model) {
        try {
            if (properties.isPseudoop()) {
                webHdfsService.uploadFile(file);
            } else {
                storageService.store(file);
            }
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