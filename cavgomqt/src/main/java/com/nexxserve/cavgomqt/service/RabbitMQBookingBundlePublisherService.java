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
        try {
            logger.info("📤 Publishing booking bundle for trip_id={} booking_id={}",
                    bookingBundle != null ? bookingBundle.tripId : "null",
                    bookingBundle != null && bookingBundle.booking != null ? bookingBundle.booking.id : "null");
            rabbitTemplate.convertAndSend(RabbitMQConfig.BOOKINGS_BUNDLE_QUEUE, bookingBundle);
            logger.info("✅ Booking bundle published to queue {}", RabbitMQConfig.BOOKINGS_BUNDLE_QUEUE);
        } catch (Exception e) {
            logger.error("❌ Failed to publish booking bundle: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to publish booking bundle to RabbitMQ", e);
        }
    }
}


