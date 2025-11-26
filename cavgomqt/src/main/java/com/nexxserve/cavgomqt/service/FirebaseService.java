package com.nexxserve.cavgomqt.service;

import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Service for sending Firebase Cloud Messaging (FCM) messages to topics.
 */
@Service
public class FirebaseService {

    private static final Logger logger = LoggerFactory.getLogger(FirebaseService.class);

    @Autowired
    private FirebaseMessaging firebaseMessaging;

    @Value("${firebase.topic.default:general}")
    private String defaultTopic;

    /**
     * Sends a "hello" message to the specified topic.
     * 
     * @param topic The topic name to send the message to
     * @return The message ID if successful, null otherwise
     */
    public String sendHelloToTopic(String topic) {
        return sendMessageToTopic(topic, Map.of("message", "hello"));
    }

    /**
     * Sends a "hello" message to the default topic configured in application properties.
     * 
     * @return The message ID if successful, null otherwise
     */
    public String sendHelloToDefaultTopic() {
        logger.info("📤 Sending 'hello' message to default topic: {}", defaultTopic);
        return sendHelloToTopic(defaultTopic);
    }

    /**
     * Sends a message with custom data to the specified topic.
     * 
     * @param topic The topic name to send the message to (can be optionally prefixed with "/topics/")
     * @param data A map of key-value pairs to include in the message data payload
     * @return The message ID if successful, null otherwise
     */
    public String sendMessageToTopic(String topic, Map<String, String> data) {
        try {
            if (topic == null || topic.isEmpty()) {
                logger.error("❌ Topic cannot be null or empty");
                return null;
            }

            // Remove "/topics/" prefix if present (Firebase handles both formats)
            String cleanTopic = topic.startsWith("/topics/") ? topic.substring(8) : topic;

            logger.info("📤 Sending message to topic: {}", cleanTopic);
            logger.debug("📦 Message data: {}", data);

            // Build the message
            Message.Builder messageBuilder = Message.builder()
                .setTopic(cleanTopic);

            // Add all data fields
            if (data != null) {
                for (Map.Entry<String, String> entry : data.entrySet()) {
                    messageBuilder.putData(entry.getKey(), entry.getValue());
                }
            }

            Message message = messageBuilder.build();

            // Send the message
            String response = firebaseMessaging.send(message);

            logger.info("✅ Successfully sent message to topic '{}': {}", cleanTopic, response);
            return response;

        } catch (FirebaseMessagingException e) {
            logger.error("❌ Failed to send message to topic '{}': {}", topic, e.getMessage(), e);
            return null;
        } catch (Exception e) {
            logger.error("❌ Unexpected error while sending message to topic '{}': {}", topic, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Sends a message with custom data to the default topic.
     * 
     * @param data A map of key-value pairs to include in the message data payload
     * @return The message ID if successful, null otherwise
     */
    public String sendMessageToDefaultTopic(Map<String, String> data) {
        logger.info("📤 Sending message to default topic: {}", defaultTopic);
        return sendMessageToTopic(defaultTopic, data);
    }

    /**
     * Sends a message with custom data and collapse key to the specified topic.
     * Collapse keys are used to ensure multiple messages are received even when device is offline.
     * 
     * @param topic The topic name to send the message to (can be optionally prefixed with "/topics/")
     * @param collapseKey The collapse key to use for message collapsing (different keys ensure separate delivery)
     * @param data A map of key-value pairs to include in the message data payload
     * @return The message ID if successful, null otherwise
     */
    public String sendMessageToTopicWithCollapseKey(String topic, String collapseKey, Map<String, String> data) {
        try {
            if (topic == null || topic.isEmpty()) {
                logger.error("❌ Topic cannot be null or empty");
                return null;
            }

            // Remove "/topics/" prefix if present (Firebase handles both formats)
            String cleanTopic = topic.startsWith("/topics/") ? topic.substring(8) : topic;

            logger.info("📤 Sending message to topic: {} with collapse key: {}", cleanTopic, collapseKey);
            logger.debug("📦 Message data: {}", data);

            // Build the message
            Message.Builder messageBuilder = Message.builder()
                .setTopic(cleanTopic);

            // Set collapse key via AndroidConfig if provided
            if (collapseKey != null && !collapseKey.isEmpty()) {
                AndroidConfig androidConfig = AndroidConfig.builder()
                    .setCollapseKey(collapseKey)
                    .build();
                messageBuilder.setAndroidConfig(androidConfig);
            }

            // Add all data fields
            if (data != null) {
                for (Map.Entry<String, String> entry : data.entrySet()) {
                    messageBuilder.putData(entry.getKey(), entry.getValue());
                }
            }

            Message message = messageBuilder.build();

            // Send the message
            String response = firebaseMessaging.send(message);

            logger.info("✅ Successfully sent message to topic '{}' with collapse key '{}': {}", 
                       cleanTopic, collapseKey, response);
            return response;

        } catch (FirebaseMessagingException e) {
            logger.error("❌ Failed to send message to topic '{}' with collapse key '{}': {}", 
                        topic, collapseKey, e.getMessage(), e);
            return null;
        } catch (Exception e) {
            logger.error("❌ Unexpected error while sending message to topic '{}' with collapse key '{}': {}", 
                        topic, collapseKey, e.getMessage(), e);
            return null;
        }
    }
}

