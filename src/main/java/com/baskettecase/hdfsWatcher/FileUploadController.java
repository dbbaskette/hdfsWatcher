package com.baskettecase.hdfsWatcher;

import com.baskettecase.hdfsWatcher.util.HdfsWatcherConstants;
import com.baskettecase.hdfsWatcher.util.UrlUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Controller for file upload operations with proper logging and validation.
 */
@Controller
public class FileUploadController {
    
    private static final Logger logger = LoggerFactory.getLogger(FileUploadController.class);

    private final LocalFileService storageService;
    private final HdfsWatcherProperties properties;
    private final WebHdfsService webHdfsService;
    private final HdfsWatcherOutput output;

    public FileUploadController(LocalFileService storageService, 
                              HdfsWatcherProperties properties,
                              WebHdfsService webHdfsService,
                              HdfsWatcherOutput output) {
        this.storageService = validateService(storageService, "LocalFileService");
        this.properties = validateService(properties, "HdfsWatcherProperties");
        this.webHdfsService = validateService(webHdfsService, "WebHdfsService");
        this.output = validateService(output, "HdfsWatcherOutput");
        
        logger.info("FileUploadController initialized in {} mode", 
            properties.isPseudoop() ? "pseudoop" : "HDFS");
    }
    
    /**
     * Validates service dependencies.
     */
    private <T> T validateService(T service, String serviceName) {
        if (service == null) {
            throw new IllegalArgumentException(serviceName + " cannot be null");
        }
        return service;
    }

    /**
     * Lists uploaded files with proper error handling and logging.
     * 
     * @param model the Spring model for template rendering
     * @return the template name
     */
    @GetMapping("/")
    public String listUploadedFiles(Model model) {
        logger.debug("Listing uploaded files in {} mode", properties.isPseudoop() ? "pseudoop" : "local");
        
        List<String> files;
        boolean hdfsDisconnected = false;
        
        try {
            if (properties.isPseudoop()) {
                files = webHdfsService.listFiles();
                logger.debug("Retrieved {} files from WebHDFS", files.size());
            } else {
                files = storageService.loadAll()
                    .map(path -> path.getFileName().toString())
                    .collect(Collectors.toList());
                logger.debug("Retrieved {} files from local storage", files.size());
            }
        } catch (Exception e) {
            logger.error("Error listing files in {} mode", properties.isPseudoop() ? "pseudoop" : "local", e);
            files = List.of();
            hdfsDisconnected = properties.isPseudoop();
            
            String errorMessage = properties.isPseudoop() ? 
                "HDFS is disconnected: " + e.getMessage() : 
                "Local storage error: " + e.getMessage();
            model.addAttribute("message", errorMessage);
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

    /**
     * Handles file upload with proper validation, logging, and URL encoding.
     * 
     * @param file the uploaded file
     * @param model the Spring model for template rendering
     * @return the redirect target
     */
    @PostMapping("/")
    public String handleFileUpload(@RequestParam("file") MultipartFile file, Model model) {
        if (file == null || file.isEmpty()) {
            logger.warn("Attempted to upload null or empty file");
            model.addAttribute("message", "Please select a file to upload");
            return "redirect:/";
        }
        
        String originalFilename = file.getOriginalFilename();
        logger.info("Handling file upload: {} ({} bytes) in {} mode", 
            originalFilename, file.getSize(), properties.isPseudoop() ? "pseudoop" : "local");
        
        try {
            String publicUrl = processFileUpload(file, originalFilename);
            
            // Always send JSON notification to Rabbit/stream
            output.send(publicUrl, properties.getMode());
            
            model.addAttribute("message", 
                "You successfully uploaded " + originalFilename + "!");
            
            logger.info("Successfully uploaded file: {} -> {}", originalFilename, publicUrl);
            return "redirect:/";
            
        } catch (Exception e) {
            logger.error("Failed to upload file: {}", originalFilename, e);
            model.addAttribute("message", 
                "Failed to upload file: " + originalFilename + ". Error: " + e.getMessage());
            return "redirect:/";
        }
    }
    
    /**
     * Processes file upload and returns the public URL.
     */
    private String processFileUpload(MultipartFile file, String originalFilename) throws Exception {
        if (properties.isPseudoop()) {
            return processWebHdfsUpload(file, originalFilename);
        } else {
            return processLocalUpload(file);
        }
    }
    
    /**
     * Processes upload to WebHDFS and builds the public URL.
     */
    private String processWebHdfsUpload(MultipartFile file, String originalFilename) throws Exception {
        webHdfsService.uploadFile(file);
        
        String baseUrl = properties.getWebhdfsUri();
        String hdfsPath = properties.getHdfsPath();
        String user = properties.getHdfsUser();
        
        // Normalize paths
        baseUrl = baseUrl.replaceAll("/+$", "");
        if (!hdfsPath.startsWith("/")) {
            hdfsPath = "/" + hdfsPath;
        }
        
        String encodedFilename = UrlUtils.encodeFilename(originalFilename);
        return String.format("%s%s%s/%s?op=%s&user.name=%s", 
            baseUrl, 
            HdfsWatcherConstants.WEBHDFS_PATH,
            hdfsPath, 
            encodedFilename, 
            HdfsWatcherConstants.WEBHDFS_OP_OPEN, 
            user);
    }
    
    /**
     * Processes upload to local storage and builds the public URL.
     */
    private String processLocalUpload(MultipartFile file) {
        return storageService.store(file);
    }

    /**
     * Handles runtime exceptions with proper logging.
     * 
     * @param exc the runtime exception
     * @return the error response
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<?> handleStorageFileNotFound(RuntimeException exc) {
        logger.error("Handling RuntimeException in FileUploadController", exc);
        return ResponseEntity.notFound().build();
    }
}