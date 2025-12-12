package com.nexxserve.cavgomqt.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.rabbitmq.client.Connection;

/**
 * Component to log RabbitMQ connection status on application startup.
 */
@Component
public class RabbitMQConnectionLogger {

    private static final Logger logger = LoggerFactory.getLogger(RabbitMQConnectionLogger.class);

    @Autowired
    private ConnectionFactory connectionFactory;

    @EventListener(ApplicationReadyEvent.class)
    public void logRabbitMQConnection() {
        try {
            logger.info("═══════════════════════════════════════════════════════════════");
            logger.info("🔌 RABBITMQ CONNECTION STATUS");
            logger.info("═══════════════════════════════════════════════════════════════");
            
            org.springframework.amqp.rabbit.connection.Connection springConnection = connectionFactory.createConnection();
            Connection connection = springConnection.getDelegate();
            if (connection != null && connection.isOpen()) {
                logger.info("✅ RabbitMQ connection is ACTIVE");
                logger.info("  - Host: {}", connection.getAddress() != null ? connection.getAddress().getHostName() : "Unknown");
                logger.info("  - Virtual Host: {}", connection.getServerProperties() != null ? connection.getServerProperties().get("virtual_host") : "Unknown");
            } else {
                logger.warn("⚠️  RabbitMQ connection is NOT active");
            }
            
            logger.info("═══════════════════════════════════════════════════════════════");
        } catch (Exception e) {
            logger.error("❌ Error checking RabbitMQ connection: {}", e.getMessage(), e);
        }
    }
}

