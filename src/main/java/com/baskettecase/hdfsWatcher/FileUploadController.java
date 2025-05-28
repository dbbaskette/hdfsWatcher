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
    // HdfsWatcherOutput is no longer strictly needed here if we remove the direct send,
    // but keeping it doesn't harm if other methods might use it (though none currently do).
    // For this specific change, 'output' field and constructor param can be removed if not used elsewhere.
    // For now, let's assume it might be used later and just comment out its usage.
    // private final HdfsWatcherOutput output; // Optionally remove if no other uses

    public FileUploadController(LocalFileService storageService, 
                              HdfsWatcherProperties properties,
                              HdfsWatcherOutput output) { // 'output' can be removed from constructor if field is removed
        this.storageService = storageService;
        this.properties = properties;
        // this.output = output; // Optionally remove
    }

    @GetMapping("/")
    public String listUploadedFiles(Model model) {
        List<String> files = storageService.loadAll()
            .map(path -> path.getFileName().toString()) // Changed to getFileName() for consistency with what users see. Path.toString() on relativized path is fine too.
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
            // The store method saves the file and returns its public URL.
            // We don't need the URL in the controller anymore if we're not sending from here.
            storageService.store(file); 
            
            // The HdfsWatcherService will now be solely responsible for detecting 
            // this new file during its polling cycle and sending the message.
            // --- REMOVE THE FOLLOWING BLOCK ---
            // if (properties.isPseudoop()) {
            //     output.send(fileUrl, properties.getMode());
            // }
            // --- END OF REMOVED BLOCK ---
            
            model.addAttribute("message", 
                "You successfully uploaded " + file.getOriginalFilename() + "!");
                
            return "redirect:/";
        } catch (Exception e) {
            model.addAttribute("message", "Failed to upload file: " + file.getOriginalFilename() + ". Error: " + e.getMessage()); // Provide more context on error
            // Consider adding 'error' to model and checking in Thymeleaf for better display
            // For now, keep redirect but include error in message (or use flash attributes for robust error display on redirect)
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