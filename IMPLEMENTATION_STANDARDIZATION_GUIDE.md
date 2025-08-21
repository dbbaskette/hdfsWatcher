# HdfsWatcher API Standardization Implementation Guide

This guide provides specific code changes needed to align hdfsWatcher's API with the standardized structure used by embedProc.

## 🎯 **Overview**

Based on the API Consistency Comparison, hdfsWatcher needs the following changes:
1. **Endpoint Path**: Change `/api/processing-state` to `/api/processing/state`
2. **Response Fields**: Standardize field names and structure
3. **Timestamp Format**: Change from Unix timestamps to ISO 8601 strings
4. **Consumer Status**: Add consistent consumer status values
5. **State Change Tracking**: Add `lastChanged` and `lastChangeReason` fields

## 📁 **Files to Modify**

### 1. **FileUploadController.java**

#### Change Endpoint Path
```java
// CHANGE FROM:
@GetMapping("/api/processing-state")

// CHANGE TO:
@GetMapping("/api/processing/state")
```

#### Update Response Structure for GET /api/processing/state
```java
// CHANGE FROM:
Map<String, Object> response = new HashMap<>();
response.put("status", "success");
response.put("processingEnabled", processingStateService.isProcessingEnabled());
response.put("processingState", processingStateService.getProcessingState());
response.put("timestamp", System.currentTimeMillis());

// CHANGE TO:
Map<String, Object> response = Map.of(
    "enabled", processingStateService.isProcessingEnabled(),
    "status", processingStateService.getProcessingState(), // Will return "STARTED" or "STOPPED"
    "consumerStatus", determineConsumerStatus(processingStateService.isProcessingEnabled()),
    "lastChanged", processingStateService.getLastChanged().toString(),
    "lastChangeReason", processingStateService.getLastChangeReason(),
    "timestamp", OffsetDateTime.now(ZoneOffset.UTC).toString()
);
```

#### Add Helper Method for Consumer Status
```java
private String determineConsumerStatus(boolean processingEnabled) {
    return processingEnabled ? "CONSUMING" : "IDLE";
}
```

#### Update POST /api/processing/start Response
```java
// CHANGE FROM:
Map<String, Object> response = new HashMap<>();
response.put("status", "success");
response.put("processingEnabled", true);
response.put("processingState", "enabled");
response.put("immediatelyProcessedCount", processedCount);
response.put("message", "File processing has been ENABLED and " + processedCount + " pending files were processed immediately");
response.put("timestamp", System.currentTimeMillis());

// CHANGE TO:
Map<String, Object> response = Map.of(
    "success", true,
    "message", "Processing started successfully and " + processedCount + " pending files were processed immediately",
    "stateChanged", true, // or check if state actually changed
    "enabled", true,
    "status", "STARTED",
    "consumerStatus", "CONSUMING",
    "immediatelyProcessedCount", processedCount,
    "lastChanged", OffsetDateTime.now(ZoneOffset.UTC).toString(),
    "timestamp", OffsetDateTime.now(ZoneOffset.UTC).toString()
);
```

#### Update POST /api/processing/stop Response
```java
// CHANGE FROM:
Map<String, Object> response = new HashMap<>();
response.put("status", "success");
response.put("processingEnabled", false);
response.put("processingState", "disabled");
response.put("message", "File processing has been DISABLED");
response.put("timestamp", System.currentTimeMillis());

// CHANGE TO:
Map<String, Object> response = Map.of(
    "success", true,
    "message", "Processing stopped successfully. Files will remain in storage.",
    "stateChanged", true, // or check if state actually changed
    "enabled", false,
    "status", "STOPPED",
    "consumerStatus", "IDLE",
    "lastChanged", OffsetDateTime.now(ZoneOffset.UTC).toString(),
    "timestamp", OffsetDateTime.now(ZoneOffset.UTC).toString()
);
```

#### Update POST /api/processing/toggle Response
```java
// CHANGE FROM:
Map<String, Object> response = new HashMap<>();
response.put("status", "success");
response.put("processingEnabled", newState);
response.put("processingState", newState ? "enabled" : "disabled");
// ... existing immediate processing logic ...

// CHANGE TO:
boolean previousState = !newState; // Store before toggle
String action = newState ? "started" : "stopped";

Map<String, Object> response = Map.of(
    "success", true,
    "message", String.format("Processing %s successfully. Previous state: %s, Current state: %s. %s",
        action,
        previousState ? "enabled" : "disabled",
        newState ? "enabled" : "disabled",
        newState ? processedCount + " pending files were processed immediately." : "Files will remain in storage."),
    "action", action,
    "previousState", Map.of(
        "enabled", previousState,
        "status", previousState ? "STARTED" : "STOPPED"
    ),
    "currentState", Map.of(
        "enabled", newState,
        "status", newState ? "STARTED" : "STOPPED",
        "consumerStatus", newState ? "CONSUMING" : "IDLE"
    ),
    "immediatelyProcessedCount", newState ? processedCount : null,
    "lastChanged", OffsetDateTime.now(ZoneOffset.UTC).toString(),
    "timestamp", OffsetDateTime.now(ZoneOffset.UTC).toString()
);
```

#### Update Other Endpoints with Timestamp Format
Replace all instances of:
```java
response.put("timestamp", System.currentTimeMillis());
```

With:
```java
response.put("timestamp", OffsetDateTime.now(ZoneOffset.UTC).toString());
```

#### Update Status Fields in Other Endpoints
In `/api/status` and `/api/files` endpoints, change:
```java
// CHANGE FROM:
response.put("processingEnabled", processingStateService.isProcessingEnabled());
response.put("processingState", processingStateService.getProcessingState());

// CHANGE TO:
response.put("enabled", processingStateService.isProcessingEnabled());
response.put("status", processingStateService.getProcessingState()); // Returns "STARTED" or "STOPPED"
response.put("consumerStatus", determineConsumerStatus(processingStateService.isProcessingEnabled()));
```

### 2. **ProcessingStateService.java**

#### Add State Change Tracking Fields
```java
public class ProcessingStateService {
    private static final Logger logger = LoggerFactory.getLogger(ProcessingStateService.class);
    
    private volatile boolean isProcessingEnabled = false; // Default to stopped
    
    // ADD THESE FIELDS:
    private volatile OffsetDateTime lastStateChange = OffsetDateTime.now(ZoneOffset.UTC);
    private volatile String lastChangeReason = "Application startup";
```

#### Update State Change Methods
```java
public void enableProcessing() {
    isProcessingEnabled = true;
    lastStateChange = OffsetDateTime.now(ZoneOffset.UTC);
    lastChangeReason = "Processing enabled via API";
    logger.info("File processing has been ENABLED");
}

public void disableProcessing() {
    isProcessingEnabled = false;
    lastStateChange = OffsetDateTime.now(ZoneOffset.UTC);
    lastChangeReason = "Processing disabled via API";
    logger.info("File processing has been DISABLED");
}

public boolean toggleProcessing() {
    isProcessingEnabled = !isProcessingEnabled;
    lastStateChange = OffsetDateTime.now(ZoneOffset.UTC);
    lastChangeReason = isProcessingEnabled ? "Processing enabled via toggle" : "Processing disabled via toggle";
    logger.info("File processing has been {}", isProcessingEnabled ? "ENABLED" : "DISABLED");
    return isProcessingEnabled;
}
```

#### Update getProcessingState Method
```java
// CHANGE FROM:
public String getProcessingState() {
    return isProcessingEnabled ? "enabled" : "disabled";
}

// CHANGE TO:
public String getProcessingState() {
    return isProcessingEnabled ? "STARTED" : "STOPPED";
}
```

#### Add New Getter Methods
```java
public OffsetDateTime getLastChanged() {
    return lastStateChange;
}

public String getLastChangeReason() {
    return lastChangeReason;
}
```

### 3. **Add Required Imports**

In **FileUploadController.java**:
```java
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
```

In **ProcessingStateService.java**:
```java
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
```

## 🔧 **Implementation Steps**

### Step 1: Update ProcessingStateService
1. Add state change tracking fields
2. Update state change methods to track timestamps and reasons
3. Change `getProcessingState()` to return "STARTED"/"STOPPED"
4. Add new getter methods

### Step 2: Update FileUploadController
1. Change endpoint path from `/api/processing-state` to `/api/processing/state`
2. Add helper method for consumer status
3. Update all response structures to use standardized field names
4. Change all timestamps from Unix to ISO 8601 format
5. Add detailed state change information to responses

### Step 3: Test the Changes
1. Test all endpoints with the new response format
2. Verify timestamps are in ISO 8601 format
3. Confirm field names match the standardized structure
4. Test state change tracking works correctly

### Step 4: Update Documentation
The API_ENDPOINTS_GUIDE.md has already been updated with the new standardized format.

## 🎉 **Expected Results**

After implementation:
- ✅ Endpoint path: `/api/processing/state` (consistent)
- ✅ Field names: `enabled`, `status`, `consumerStatus` (consistent)
- ✅ Status values: `"STARTED"`, `"STOPPED"` (consistent)
- ✅ Consumer status: `"CONSUMING"`, `"IDLE"` (consistent)
- ✅ Timestamps: ISO 8601 format (consistent)
- ✅ State tracking: `lastChanged`, `lastChangeReason` (enhanced)
- ✅ Response structure: Matches embedProc pattern (consistent)

The hdfsWatcher API will then be fully aligned with the standardized structure while preserving its unique file management capabilities.
