package com.nexxserve.cavgomqt.service;

import com.nexxserve.cavgomqt.config.RabbitMQConfig;
import com.nexxserve.cavgomqt.dto.mqtt.BookingBundle;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RabbitMQBookingBundlePublisherService {

    private static final Logger logger = LoggerFactory.getLogger(RabbitMQBookingBundlePublisherService.class);

    private final RabbitTemplate rabbitTemplate;

    public void publish(BookingBundle bookingBundle) {
        logger.info("📤 === PUBLISHING BOOKING BUNDLE TO RABBITMQ ===");
        logger.info("  - Queue: {}", RabbitMQConfig.BOOKINGS_BUNDLE_QUEUE);
        logger.info("  - Timestamp: {}", System.currentTimeMillis());
        
        try {
            // Validate input
            if (bookingBundle == null) {
                logger.error("❌ Booking bundle is null - cannot publish");
                throw new IllegalArgumentException("Booking bundle cannot be null");
            }
            
            logger.info("  - Trip ID: {}", bookingBundle.tripId != null ? bookingBundle.tripId : "null");
            logger.info("  - Booking ID: {}", bookingBundle.booking != null ? bookingBundle.booking.id : "null");
            logger.info("  - Payment ID: {}", bookingBundle.payment != null ? bookingBundle.payment.id : "null");
            logger.info("  - Ticket ID: {}", bookingBundle.ticket != null ? bookingBundle.ticket.id : "null");
            
            // Attempt to publish
            logger.info("📤 Sending booking bundle to RabbitMQ queue: {}", RabbitMQConfig.BOOKINGS_BUNDLE_QUEUE);
            rabbitTemplate.convertAndSend(RabbitMQConfig.BOOKINGS_BUNDLE_QUEUE, bookingBundle);
            
            logger.info("✅ SUCCESS: Booking bundle published to RabbitMQ");
            logger.info("  - Queue: {}", RabbitMQConfig.BOOKINGS_BUNDLE_QUEUE);
            logger.info("  - Trip ID: {}", bookingBundle.tripId);
            logger.info("  - Booking ID: {}", bookingBundle.booking != null ? bookingBundle.booking.id : "null");
            
        } catch (Exception e) {
            logger.error("❌ FAILED to publish booking bundle to RabbitMQ:");
            logger.error("  - Queue: {}", RabbitMQConfig.BOOKINGS_BUNDLE_QUEUE);
            logger.error("  - Trip ID: {}", bookingBundle != null ? bookingBundle.tripId : "null");
            logger.error("  - Booking ID: {}", bookingBundle != null && bookingBundle.booking != null ? bookingBundle.booking.id : "null");
            logger.error("  - Error: {}", e.getMessage());
            logger.error("  - Exception type: {}", e.getClass().getSimpleName());
            e.printStackTrace();
            throw new RuntimeException("Failed to publish booking bundle to RabbitMQ", e);
        }
    }
}


