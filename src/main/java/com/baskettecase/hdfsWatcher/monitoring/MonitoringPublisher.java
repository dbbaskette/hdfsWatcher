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

    public MonitoringPublisher(MonitoringProperties props,
                               RabbitTemplate rabbitTemplate,
                               ProcessingStateService processingStateService,
                               HdfsWatcherProperties hdfsProps,
                               AmqpAdmin amqpAdmin) {
        this.props = props;
        this.rabbitTemplate = rabbitTemplate;
        this.processingStateService = processingStateService;
        this.hdfsProps = hdfsProps;
        this.amqpAdmin = amqpAdmin;
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
        if (!props.isRabbitmqEnabled()) {
            return;
        }
        try {
            Map<String, Object> payload = buildPayload();
            String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload);
            rabbitTemplate.convertAndSend("", props.getQueueName(), json);
            logger.debug("Published monitoring message to {}", props.getQueueName());
        } catch (Exception e) {
            logger.warn("Failed to publish monitoring message", e);
        }
    }

    private Map<String, Object> buildInitPayload() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("instanceId", instanceId());
        m.put("timestamp", OffsetDateTime.now().toString());
        m.put("status", status());
        m.put("uptime", "0s");
        m.put("hostname", hdfsProps.getHostname());
        String publicHost = hdfsProps.getPublicHostname();
        if (publicHost != null) {
            m.put("publicHostname", publicHost);
        }
        m.put("meta", Map.of("service", "hdfsWatcher"));
        return m;
    }

    private Map<String, Object> buildPayload() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("instanceId", instanceId());
        m.put("timestamp", OffsetDateTime.now().toString());
        m.put("status", status());
        m.put("uptime", uptime());

        m.put("hostname", hdfsProps.getHostname());
        String publicHost = hdfsProps.getPublicHostname();
        if (publicHost != null) {
            m.put("publicHostname", publicHost);
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

        m.put("meta", Map.of("service", "hdfsWatcher"));
        return m;
    }

    private String status() {
        return processingStateService.isProcessingEnabled() ? "PROCESSING" : "DISABLED";
    }

    private String instanceId() {
        if (props.getInstanceId() != null && !props.getInstanceId().isBlank()) {
            return props.getInstanceId();
        }
        // Default: appname-pid
        String pid = ManagementFactory.getRuntimeMXBean().getName();
        return "hdfsWatcher-" + pid;
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
}


