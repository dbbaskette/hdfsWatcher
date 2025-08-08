package com.baskettecase.hdfsWatcher.monitoring;

import com.baskettecase.hdfsWatcher.HdfsWatcherProperties;
import com.baskettecase.hdfsWatcher.service.ProcessingStateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Queue;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.core.env.Environment;

import java.lang.management.ManagementFactory;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "app.monitoring", name = "rabbitmq-enabled", havingValue = "true")
public class MonitoringPublisher {

    private static final Logger logger = LoggerFactory.getLogger(MonitoringPublisher.class);

    private final MonitoringProperties props;
    private final RabbitTemplate rabbitTemplate;
    private final ProcessingStateService processingStateService;
    private final AmqpAdmin amqpAdmin;
    private final HdfsWatcherProperties hdfsProps;
    private final Environment environment;

    public MonitoringPublisher(MonitoringProperties props,
                               RabbitTemplate rabbitTemplate,
                               ProcessingStateService processingStateService,
                               HdfsWatcherProperties hdfsProps,
                               AmqpAdmin amqpAdmin,
                               Environment environment) {
        this.props = props;
        this.rabbitTemplate = rabbitTemplate;
        this.processingStateService = processingStateService;
        this.hdfsProps = hdfsProps;
        this.amqpAdmin = amqpAdmin;
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void emitInit() {
        if (!props.isRabbitmqEnabled()) {
            return;
        }
        try {
            // Ensure monitoring queue exists if auto-declare is enabled
            if (props.isRabbitmqAutoDeclare()) {
                Queue q = new Queue(props.getQueueName(), true);
                amqpAdmin.declareQueue(q);
            }
            Map<String, Object> init = buildInitPayload();
            String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(init);
            rabbitTemplate.convertAndSend("", props.getQueueName(), json);
            logger.info("Published monitoring init message to {}", props.getQueueName());
        } catch (Exception e) {
            logger.warn("Failed to publish monitoring init message", e);
        }
    }

    @Scheduled(fixedDelayString = "${app.monitoring.emit-interval-seconds:10}000")
    public void emit() {
        if (!props.isRabbitmqEnabled() || !props.isEmitHeartbeats()) {
            return;
        }
        try {
            Map<String, Object> payload = buildPayload();
            String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload);
            rabbitTemplate.convertAndSend("", props.getQueueName(), json);
            logger.debug("Published monitoring heartbeat to {}", props.getQueueName());
        } catch (Exception e) {
            logger.warn("Failed to publish monitoring heartbeat", e);
        }
    }

    private Map<String, Object> buildInitPayload() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("instanceId", instanceId());
        m.put("timestamp", OffsetDateTime.now().toString());
        m.put("event", "INIT");
        m.put("status", status());
        m.put("uptime", "0s");
        m.put("hostname", hdfsProps.getHostname());
        String publicHost = hdfsProps.getPublicHostname();
        if (publicHost != null) {
            m.put("publicHostname", publicHost);
        }
        Integer port = resolveServerPort();
        if (port != null) {
            m.put("port", port);
        }
        String internalUrl = buildInternalUrl(port);
        if (internalUrl != null) {
            m.put("internalUrl", internalUrl);
        }
        String publicUrl = hdfsProps.getPublicAppUri();
        if (publicUrl != null) {
            m.put("publicUrl", publicUrl);
            Integer publicPort = extractPort(publicUrl);
            if (publicPort != null) {
                m.put("publicPort", publicPort);
            }
        }
        // Presence-spec convenience URL
        String url = (publicUrl != null) ? publicUrl : internalUrl;
        if (url != null) {
            m.put("url", url);
        }
        // Presence-spec boot epoch and version
        try {
            long bootEpoch = java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime();
            m.put("bootEpoch", bootEpoch);
        } catch (Exception ignored) {}
        if (hdfsProps.getAppVersion() != null) {
            m.put("version", hdfsProps.getAppVersion());
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("service", "hdfsWatcher");
        meta.put("bindingState", processingStateService.isProcessingEnabled() ? "running" : "stopped");
        meta.put("inputMode", hdfsProps.getMode());
        m.put("meta", meta);
        return m;
    }

    private Map<String, Object> buildPayload() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("instanceId", instanceId());
        m.put("timestamp", OffsetDateTime.now().toString());
        m.put("event", "HEARTBEAT");
        m.put("status", status());
        m.put("uptime", uptime());

        m.put("hostname", hdfsProps.getHostname());
        String publicHost = hdfsProps.getPublicHostname();
        if (publicHost != null) {
            m.put("publicHostname", publicHost);
        }
        Integer port = resolveServerPort();
        if (port != null) {
            m.put("port", port);
        }
        String internalUrl = buildInternalUrl(port);
        if (internalUrl != null) {
            m.put("internalUrl", internalUrl);
        }
        String publicUrl = hdfsProps.getPublicAppUri();
        if (publicUrl != null) {
            m.put("publicUrl", publicUrl);
            Integer publicPort = extractPort(publicUrl);
            if (publicPort != null) {
                m.put("publicPort", publicPort);
            }
        }
        String url = (publicUrl != null) ? publicUrl : internalUrl;
        if (url != null) {
            m.put("url", url);
        }

        m.put("currentFile", null);
        m.put("filesProcessed", processedCount());
        m.put("filesTotal", totalCount());

        m.put("totalChunks", 0);
        m.put("processedChunks", 0);
        m.put("processingRate", 0);

        m.put("errorCount", 0);
        m.put("lastError", null);

        long usedBytes = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        m.put("memoryUsedMB", (int)(usedBytes / (1024 * 1024)));
        m.put("pendingMessages", 0);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("service", "hdfsWatcher");
        meta.put("bindingState", processingStateService.isProcessingEnabled() ? "running" : "stopped");
        meta.put("inputMode", hdfsProps.getMode());
        m.put("meta", meta);
        return m;
    }

    private String status() {
        // RUNNING baseline; switch to PROCESSING when actively sending (emitted in file events)
        return processingStateService.isProcessingEnabled() ? "RUNNING" : "IDLE";
    }

    private String instanceId() {
        if (props.getInstanceId() != null && !props.getInstanceId().isBlank()) {
            return props.getInstanceId();
        }
        String pid = ManagementFactory.getRuntimeMXBean().getName();
        String host = hdfsProps.getHostname() != null ? hdfsProps.getHostname() : "localhost";
        return "hdfsWatcher-" + pid + "@" + host;
    }

    private String uptime() {
        long ms = ManagementFactory.getRuntimeMXBean().getUptime();
        long s = ms / 1000; long m = s / 60; long h = m / 60; s %= 60; m %= 60;
        return String.format("%dh %dm %ds", h, m, s);
    }

    private int processedCount() {
        // Could wire to ProcessedFilesService, but we keep minimal for now
        return 0;
    }

    private int totalCount() {
        return 0;
    }

    private Integer resolveServerPort() {
        try {
            Integer local = environment.getProperty("local.server.port", Integer.class);
            if (local != null && local > 0) {
                return local;
            }
            Integer configured = environment.getProperty("server.port", Integer.class);
            if (configured != null && configured > 0) {
                return configured;
            }
            // CF-style PORT env var fallback
            String envPort = System.getenv("PORT");
            if (envPort != null && !envPort.isBlank()) {
                return Integer.parseInt(envPort);
            }
            return 8080;
        } catch (Exception e) {
            return 8080;
        }
    }

    private String buildInternalUrl(Integer port) {
        try {
            if (port == null) {
                port = 8080;
            }
            String scheme = resolveScheme();
            String host = hdfsProps.getHostname();
            if (host == null || host.isBlank()) {
                host = "localhost";
            }
            return scheme + "://" + host + ":" + port;
        } catch (Exception e) {
            return null;
        }
    }

    private String resolveScheme() {
        try {
            Boolean ssl = environment.getProperty("server.ssl.enabled", Boolean.class, false);
            return (ssl != null && ssl) ? "https" : "http";
        } catch (Exception e) {
            return "http";
        }
    }

    private Integer extractPort(String url) {
        try {
            java.net.URI uri = new java.net.URI(url);
            int p = uri.getPort();
            return p >= 0 ? p : null;
        } catch (Exception e) {
            return null;
        }
    }
}


