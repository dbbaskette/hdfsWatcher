## RabbitMQ Monitoring Message — Shared Schema and How-To

This guide standardizes the monitoring event published by services in the pipeline (e.g., `hdfsWatcher`, `textProc`, `embedProc`). It defines the JSON schema, required application properties, and reference code to emit periodic monitoring messages to a shared RabbitMQ queue.

Reference inspiration: `embedProc` monitoring doc (metrics-style message). Aligns fields across apps to simplify dashboards.

### 1) Application Properties (all services)

Add these properties (override per environment):

```properties
# Enable publishing of monitoring messages
app.monitoring.rabbitmq.enabled=true

# Queue to publish monitoring messages to (single shared queue across services)
app.monitoring.rabbitmq.queue-name=pipeline.metrics

# Optional: set a stable instance id; default could include app name + index
app.monitoring.instance-id=

# How often to publish (seconds)
app.monitoring.emit-interval-seconds=10
```

Optional: if using Spring AMQP directly, also configure RabbitMQ connection (`spring.rabbitmq.*`) as usual. If using Spring Cloud Stream, configure a binding for a dedicated monitoring output.

### 2) JSON Schema (with host info)

```json
{
  "type": "object",
  "properties": {
    "instanceId": { "type": "string" },
    "timestamp": { "type": "string", "format": "date-time" },
    "status": { "type": "string", "enum": ["PROCESSING", "DISABLED", "IDLE", "ERROR"] },
    "uptime": { "type": "string" },

    "hostname": { "type": "string", "description": "Internal FQDN (InetAddress.canonicalHostName)" },
    "publicHostname": { "type": "string", "description": "Routable host from publicAppUri (CF route)" },

    "currentFile": { "type": ["string", "null"] },
    "filesProcessed": { "type": "number" },
    "filesTotal": { "type": "number" },

    "totalChunks": { "type": "number" },
    "processedChunks": { "type": "number" },
    "processingRate": { "type": "number" },

    "errorCount": { "type": "number" },
    "lastError": { "type": ["string", "null"] },

    "memoryUsedMB": { "type": "number" },
    "pendingMessages": { "type": ["number", "null"] },

    "meta": {
      "type": "object",
      "properties": {
        "service": { "type": "string" }
      },
      "required": ["service"]
    }
  },
  "required": ["instanceId", "timestamp", "status", "meta"]
}
```

Notes:
- For apps without chunking (e.g., `hdfsWatcher`), set `totalChunks`/`processedChunks` to 0 or omit.
- `status` conventions:
  - `PROCESSING` = enabled and active
  - `DISABLED` = processing disabled
  - `IDLE` = enabled but no work pending
  - `ERROR` = unrecoverable error state

### 3) Example Message (from hdfsWatcher)

```json
{
  "instanceId": "hdfsWatcher-0",
  "timestamp": "2025-08-08T14:23:45Z",
  "status": "PROCESSING",
  "uptime": "1h 12m",

  "hostname": "ip-10-0-1-23.ec2.internal",
  "publicHostname": "hdfswatcher-blue.cfapps.io",

  "currentFile": "customer_data_2025_08_08.csv",
  "filesProcessed": 128,
  "filesTotal": 135,

  "totalChunks": 0,
  "processedChunks": 0,
  "processingRate": 3.4,

  "errorCount": 1,
  "lastError": "WebHDFS LISTSTATUS timeout (retrying)",

  "memoryUsedMB": 420,
  "pendingMessages": 0,
  "meta": { "service": "hdfsWatcher" }
}
```

### 4) Implementation Steps (Spring Boot)

#### a) Bind properties

```java
@ConfigurationProperties(prefix = "app.monitoring")
@Data
public class MonitoringProperties {
  private boolean rabbitmqEnabled = false;
  private String queueName = "pipeline.metrics";
  private String instanceId; // e.g., ${spring.application.name}-${CF_INSTANCE_INDEX:0}
  private int emitIntervalSeconds = 10;
}
```

#### b) Resolve hostnames (works local and Cloud Foundry)

```java
String hostname = java.net.InetAddress.getLocalHost().getCanonicalHostName();

String publicHost = null;
try {
  String publicUri = hdfsWatcherProperties.getPublicAppUri(); // or equivalent in your service
  publicHost = new java.net.URI(publicUri).getHost();
} catch (Exception ignored) {}
```

#### c) Build payload

```java
Map<String, Object> payload = new LinkedHashMap<>();
payload.put("instanceId", props.getInstanceId());
payload.put("timestamp", java.time.OffsetDateTime.now().toString());
payload.put("status", computeStatus());
payload.put("uptime", humanReadableUptime());
payload.put("hostname", hostname);
if (publicHost != null) payload.put("publicHostname", publicHost);

payload.put("currentFile", currentFileNameOrNull());
payload.put("filesProcessed", filesProcessedCount());
payload.put("filesTotal", filesTotalCount());

// Optional/0 for non-chunking services
payload.put("totalChunks", 0);
payload.put("processedChunks", 0);
payload.put("processingRate", processingRatePerSec());

payload.put("errorCount", errorCount());
payload.put("lastError", lastErrorMessageOrNull());

payload.put("memoryUsedMB", (int)(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024));
payload.put("pendingMessages", 0);

payload.put("meta", Map.of("service", "hdfsWatcher" /* or textProc/embedProc */));
```

#### d) Publish to RabbitMQ

Option 1 — Spring AMQP (RabbitTemplate):

```java
@Bean
public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
  return new RabbitTemplate(connectionFactory);
}

@Scheduled(fixedDelayString = "${app.monitoring.emit-interval-seconds:10}000")
public void emitMonitoring() {
  if (!props.isRabbitmqEnabled()) return;
  String json = new ObjectMapper().writeValueAsString(buildPayload());
  rabbitTemplate.convertAndSend("", props.getQueueName(), json); // default exchange → queue
}
```

Option 2 — Spring Cloud Stream (Binder): define a `Supplier<Message<String>>` or use `StreamBridge` to send to a dedicated monitoring binding.

### 5) Cloud Foundry Notes

- Internal FQDN (`hostname`) is from the container; `publicHostname` comes from the app route parsed from environment (e.g., `VCAP_APPLICATION`).
- Ensure the monitoring queue `pipeline.metrics` exists/auto-declared and is reachable by all instances.

### 6) Status Mapping (suggested)

- `PROCESSING`: processing enabled and work was sent recently (within last interval)
- `IDLE`: processing enabled but no work detected
- `DISABLED`: processing disabled
- `ERROR`: unrecoverable condition; include `lastError`

---

Drop this file into `embedProc` and `textProc` (e.g., `RABBIT_MONITORING_INTEGRATION.md`) and wire up the emission using either RabbitTemplate or StreamBridge. Keep fields consistent across services for easy, shared dashboards.


