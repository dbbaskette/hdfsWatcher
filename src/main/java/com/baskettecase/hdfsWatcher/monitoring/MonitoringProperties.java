package com.baskettecase.hdfsWatcher.monitoring;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.monitoring")
public class MonitoringProperties {
    private boolean rabbitmqEnabled = false;
    private String queueName = "pipeline.metrics";
    private String instanceId;
    private int emitIntervalSeconds = 10;
    private boolean rabbitmqAutoDeclare = true;
    private boolean emitHeartbeats = false;

    public boolean isRabbitmqEnabled() { return rabbitmqEnabled; }
    public void setRabbitmqEnabled(boolean rabbitmqEnabled) { this.rabbitmqEnabled = rabbitmqEnabled; }
    public String getQueueName() { return queueName; }
    public void setQueueName(String queueName) { this.queueName = queueName; }
    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
    public int getEmitIntervalSeconds() { return emitIntervalSeconds; }
    public void setEmitIntervalSeconds(int emitIntervalSeconds) { this.emitIntervalSeconds = emitIntervalSeconds; }
    public boolean isRabbitmqAutoDeclare() { return rabbitmqAutoDeclare; }
    public void setRabbitmqAutoDeclare(boolean rabbitmqAutoDeclare) { this.rabbitmqAutoDeclare = rabbitmqAutoDeclare; }
    public boolean isEmitHeartbeats() { return emitHeartbeats; }
    public void setEmitHeartbeats(boolean emitHeartbeats) { this.emitHeartbeats = emitHeartbeats; }
}


