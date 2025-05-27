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
    private final HdfsWatcherOutput output;

    public FileUploadController(LocalFileService storageService, 
                              HdfsWatcherProperties properties,
                              HdfsWatcherOutput output) {
        this.storageService = storageService;
        this.properties = properties;
        this.output = output;
    }

    @GetMapping("/")
    public String listUploadedFiles(Model model) {
        List<String> files = storageService.loadAll()
            .map(path -> path.toString())
            .collect(Collectors.toList());
            
        model.addAttribute("files", files);
        model.addAttribute("isPseudoop", properties.isPseudoop());
        return "uploadForm";
    }

    @GetMapping("/files/{filename:.+}")
    @ResponseBody
    public ResponseEntity<Resource> serveFile(@PathVariable String filename) {
        Resource file = storageService.loadAsResource(filename);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"" + file.getFilename() + "\"").body(file);
    }

    @PostMapping("/")
    public String handleFileUpload(@RequestParam("file") MultipartFile file, Model model) {
        try {
            String fileUrl = storageService.store(file);
            
            // Send the file URL to the output
            if (properties.isPseudoop()) {
                output.send(fileUrl, properties.getMode());
            }
            
            model.addAttribute("message", 
                "You successfully uploaded " + file.getOriginalFilename() + "!");
                
            return "redirect:/";
        } catch (Exception e) {
            model.addAttribute("error", "Failed to upload file: " + e.getMessage());
            return "redirect:/";
        }
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<?> handleStorageFileNotFound(RuntimeException exc) {
        return ResponseEntity.notFound().build();
    }
}
