package com.baskettecase.hdfsWatcher.util;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Utility class for URL encoding operations.
 * Eliminates code duplication across multiple classes.
 */
public final class UrlUtils {
    
    private UrlUtils() {
        // Utility class - prevent instantiation
    }
    
    /**
     * Encodes a filename for use in URLs, handling special characters properly.
     * Replaces + with %20 for URL compatibility and handles parentheses correctly.
     * 
     * @param filename the filename to encode
     * @return the URL-encoded filename
     * @throws IllegalArgumentException if filename is null or empty
     */
    public static String encodeFilename(String filename) {
        if (filename == null || filename.trim().isEmpty()) {
            throw new IllegalArgumentException("Filename cannot be null or empty");
        }
        
        try {
            return URLEncoder.encode(filename, StandardCharsets.UTF_8.toString())
                    .replace("+", "%20")
                    .replace("(", "%28")
                    .replace(")", "%29");
        } catch (UnsupportedEncodingException e) {
            // This should never happen with UTF-8
            throw new RuntimeException("Failed to encode filename: " + filename, e);
        }
    }
    
    /**
     * Encodes a path segment for use in URLs.
     * 
     * @param segment the path segment to encode
     * @return the URL-encoded path segment
     * @throws IllegalArgumentException if segment is null
     */
    public static String encodePathSegment(String segment) {
        if (segment == null) {
            throw new IllegalArgumentException("Path segment cannot be null");
        }
        
        if (segment.isEmpty()) {
            return segment;
        }
        
        try {
            return URLEncoder.encode(segment, StandardCharsets.UTF_8.toString())
                    .replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            // This should never happen with UTF-8
            throw new RuntimeException("Failed to encode path segment: " + segment, e);
        }
    }
    
    /**
     * Builds a file URL with proper encoding.
     * 
     * @param baseUri the base URI
     * @param path the path component  
     * @param filename the filename
     * @return the complete URL with proper encoding
     * @throws IllegalArgumentException if any parameter is null or empty
     */
    public static String buildFileUrl(String baseUri, String path, String filename) {
        if (baseUri == null || baseUri.trim().isEmpty()) {
            throw new IllegalArgumentException("Base URI cannot be null or empty");
        }
        if (path == null) {
            throw new IllegalArgumentException("Path cannot be null");
        }
        if (filename == null || filename.trim().isEmpty()) {
            throw new IllegalArgumentException("Filename cannot be null or empty");
        }
        
        String cleanBaseUri = baseUri.replaceAll("/+$", "");
        String encodedFilename = encodeFilename(filename);
        
        return String.format("%s%s/%s", cleanBaseUri, path, encodedFilename);
    }
}