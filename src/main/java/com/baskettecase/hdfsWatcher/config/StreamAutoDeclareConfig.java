package com.baskettecase.hdfsWatcher.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Declares the stream destination exchange and a same-named queue with a catch-all binding
 * for local testing, so messages are visible without a consumer group.
 */
@Component
@ConditionalOnProperty(prefix = "app.stream", name = "auto-declare", havingValue = "true")
public class StreamAutoDeclareConfig {

    private static final Logger logger = LoggerFactory.getLogger(StreamAutoDeclareConfig.class);

    private final Environment environment;
    private final AmqpAdmin amqpAdmin;

    @Autowired
    public StreamAutoDeclareConfig(Environment environment, AmqpAdmin amqpAdmin) {
        this.environment = environment;
        this.amqpAdmin = amqpAdmin;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void declareExchangeAndQueue() {
        try {
            String destination = environment.getProperty("spring.cloud.stream.bindings.output.destination");
            if (destination == null || destination.isBlank()) {
                logger.info("[StreamAutoDeclare] No output destination configured; skipping auto-declare");
                return;
            }

            // Declare a durable topic exchange matching the destination
            TopicExchange exchange = new TopicExchange(destination, true, false);
            amqpAdmin.declareExchange(exchange);

            // Declare a durable queue with the same name and bind it to the exchange
            Queue queue = new Queue(destination, true);
            amqpAdmin.declareQueue(queue);

            Binding binding = BindingBuilder.bind(queue).to(exchange).with("#");
            amqpAdmin.declareBinding(binding);

            logger.info("[StreamAutoDeclare] Declared exchange, queue and binding for destination: {}", destination);
        } catch (Exception e) {
            logger.warn("[StreamAutoDeclare] Failed to auto-declare stream destination", e);
        }
    }
}


