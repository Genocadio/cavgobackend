package com.nexxserve.cavgomqt.config;

import com.nexxserve.cavgomqt.service.VehicleRegistryService;
import com.nexxserve.cavgomqt.service.TripReceiverService;
import com.nexxserve.cavgomqt.service.RabbitMQBookingBundlePublisherService;
import com.nexxserve.cavgomqt.service.RabbitMQVehicleLocationPublisherService;
import com.nexxserve.cavgomqt.service.NavigaService;
import com.nexxserve.cavgomqt.service.MqttLocationListenerService;
import com.nexxserve.cavgomqt.dto.mqtt.BookingBundle;
import com.nexxserve.cavgomqt.dto.incoming.IncomingVehicleStatusData;
import com.nexxserve.cavgomqt.dto.VehicleLocationUpdateMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.ApplicationListener;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.core.MessageProducer;
import org.springframework.integration.mqtt.core.DefaultMqttPahoClientFactory;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.springframework.integration.mqtt.inbound.MqttPahoMessageDrivenChannelAdapter;
import org.springframework.integration.mqtt.outbound.MqttPahoMessageHandler;
import org.springframework.integration.mqtt.support.DefaultPahoMessageConverter;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.integration.mqtt.event.MqttIntegrationEvent;
import org.springframework.integration.mqtt.event.MqttSubscribedEvent;
import org.springframework.integration.mqtt.event.MqttConnectionFailedEvent;

@Configuration
public class MqttConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(MqttConfiguration.class);

    @Autowired
    private VehicleRegistryService vehicleRegistryService;

    @Autowired
    private TripReceiverService tripReceiverService;

    @Autowired
    private RabbitMQBookingBundlePublisherService bookingBundlePublisherService;

    @Autowired
    private RabbitMQVehicleLocationPublisherService vehicleLocationPublisherService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private NavigaService navigaService;

    @Autowired
    private MqttLocationListenerService locationListenerService;

    @Value("${mqtt.broker.url:tcp://localhost:1883}")
    private String brokerUrl;

    @Value("${mqtt.client.id:backend-service}")
    private String clientId;

    @Value("${mqtt.username:}")
    private String username;

    @Value("${mqtt.password:}")
    private String password;

    @Bean
    public MqttPahoClientFactory mqttClientFactory() {
        DefaultMqttPahoClientFactory factory = new DefaultMqttPahoClientFactory();
        MqttConnectOptions options = new MqttConnectOptions();

        // Connection settings optimized for HiveMQ Cloud
        options.setCleanSession(false); // Persistent session for reliable delivery
        options.setConnectionTimeout(60); // Increased for SSL connections
        options.setKeepAliveInterval(30); // Reduced for better connection stability
        options.setAutomaticReconnect(true);
        options.setMaxInflight(50); // Reduced to prevent overwhelming the broker
        options.setMqttVersion(4); // Use MQTT 3.1.1 for better compatibility

        // SSL Configuration for HiveMQ Cloud
        if (brokerUrl.startsWith("ssl://")) {
            options.setHttpsHostnameVerificationEnabled(false); // Disable for cloud brokers
            options.setSSLProperties(new java.util.Properties());
        }

        if (!username.isEmpty()) {
            options.setUserName(username);
        }
        if (!password.isEmpty()) {
            options.setPassword(password.toCharArray());
        }

        // Do not configure Last Will to avoid offline messages on graceful or active
        // sessions

        factory.setConnectionOptions(options);
        return factory;
    }

    @Bean
    public ApplicationListener<MqttIntegrationEvent> mqttEventLogger() {
        return event -> {
            if (event instanceof MqttConnectionFailedEvent) {
                MqttConnectionFailedEvent e = (MqttConnectionFailedEvent) event;
                Throwable cause = e.getCause();
                logger.warn("MQTT connection failed: {}", cause != null ? cause.getMessage() : "unknown");
            } else if (event instanceof MqttSubscribedEvent) {
                logger.info("MQTT subscribed: {}", ((MqttSubscribedEvent) event).getMessage());
            } else if ("org.springframework.integration.mqtt.event.MqttConnectionEstablishedEvent"
                    .equals(event.getClass().getName())) {
                logger.info("MQTT connected: {}", event.getSource());
            } else if (event instanceof MqttSubscribedEvent) {
                logger.info("MQTT subscribed: {}", ((MqttSubscribedEvent) event).getMessage());
            } else {
                logger.debug("MQTT event: {}", event.toString());
            }
        };
    }

    // === INBOUND CHANNELS ===

    @Bean
    public MessageChannel carStatusChannel() {
        return new DirectChannel();
    }

    @Bean
    public MessageChannel tripUpdatesChannel() {
        return new DirectChannel();
    }

    @Bean
    public MessageChannel heartbeatChannel() {
        return new DirectChannel();
    }

    @Bean
    public MessageChannel heartbeatInboundChannel() {
        return new DirectChannel();
    }

    @Bean
    public MessageChannel bookingBundleChannel() {
        return new DirectChannel();
    }

    @Bean
    public MessageChannel locationBatchChannel() {
        return new DirectChannel();
    }

    // === OUTBOUND CHANNELS ===

    @Bean
    public MessageChannel tripAssignmentChannel() {
        return new DirectChannel();
    }

    @Bean
    public MessageChannel bookingUpdatesChannel() {
        return new DirectChannel();
    }

    @Bean
    public MessageChannel heartbeatOutboundChannel() {
        return new DirectChannel();
    }

    @Bean
    public MessageChannel bookingBundleOutboundChannel() {
        return new DirectChannel();
    }

    @Bean
    public MessageChannel vehicleSettingsOutboundChannel() {
        return new DirectChannel();
    }

    // === INBOUND ADAPTERS ===

    @Bean
    public MessageProducer carStatusInbound() {
        MqttPahoMessageDrivenChannelAdapter adapter = new MqttPahoMessageDrivenChannelAdapter(
                brokerUrl,
                clientId + "-status-" + System.currentTimeMillis(),
                mqttClientFactory(),
                "car/+/status");
        adapter.setCompletionTimeout(10000); // Increased timeout for SSL
        adapter.setConverter(new DefaultPahoMessageConverter());
        adapter.setQos(1);
        adapter.setOutputChannel(carStatusChannel());
        return adapter;
    }

    @Bean
    public MessageProducer tripUpdatesInbound() {
        MqttPahoMessageDrivenChannelAdapter adapter = new MqttPahoMessageDrivenChannelAdapter(
                brokerUrl,
                clientId + "-trip-updates-" + System.currentTimeMillis(),
                mqttClientFactory(),
                "car/+/trip/updates");
        adapter.setCompletionTimeout(10000); // Increased timeout for SSL
        adapter.setConverter(new DefaultPahoMessageConverter());
        adapter.setQos(1);
        adapter.setOutputChannel(tripUpdatesChannel());
        return adapter;
    }

    @Bean
    public MessageProducer heartbeatInbound() {
        MqttPahoMessageDrivenChannelAdapter adapter = new MqttPahoMessageDrivenChannelAdapter(
                brokerUrl,
                clientId + "-heartbeat-" + System.currentTimeMillis(),
                mqttClientFactory(),
                "car/+/pong");
        adapter.setCompletionTimeout(10000); // Increased timeout for SSL
        adapter.setConverter(new DefaultPahoMessageConverter());
        adapter.setQos(1);
        adapter.setOutputChannel(heartbeatChannel());
        return adapter;
    }

    @Bean
    public MessageProducer vehicleHeartbeatInbound() {
        MqttPahoMessageDrivenChannelAdapter adapter = new MqttPahoMessageDrivenChannelAdapter(
                brokerUrl,
                clientId + "-vehicle-heartbeat-" + System.currentTimeMillis(),
                mqttClientFactory(),
                "car/+/heartbeat");
        adapter.setCompletionTimeout(10000); // Increased timeout for SSL
        adapter.setConverter(new DefaultPahoMessageConverter());
        adapter.setQos(1);
        adapter.setOutputChannel(heartbeatInboundChannel());
        return adapter;
    }

    @Bean
    public MessageProducer bookingBundleInbound() {
        MqttPahoMessageDrivenChannelAdapter adapter = new MqttPahoMessageDrivenChannelAdapter(
                brokerUrl,
                clientId + "-booking-bundle-" + System.currentTimeMillis(),
                mqttClientFactory(),
                // Listen only to inbound bundles from edge devices to avoid reply loop
                "trip/+/booking_bundle/inbound");
        adapter.setCompletionTimeout(10000);
        adapter.setConverter(new DefaultPahoMessageConverter());
        adapter.setQos(1);
        adapter.setOutputChannel(bookingBundleChannel());
        return adapter;
    }

    @Bean
    public MessageProducer locationBatchInbound() {
        MqttPahoMessageDrivenChannelAdapter adapter = new MqttPahoMessageDrivenChannelAdapter(
                brokerUrl,
                clientId + "-location-batch-" + System.currentTimeMillis(),
                mqttClientFactory(),
                "vehicles/+/location/batch");
        adapter.setCompletionTimeout(10000);
        DefaultPahoMessageConverter converter = new DefaultPahoMessageConverter();
        converter.setPayloadAsBytes(true); // Ensure protobuf payload stays binary
        adapter.setConverter(converter);
        adapter.setQos(1); // QoS 1 as per documentation
        adapter.setOutputChannel(locationBatchChannel());
        return adapter;
    }

    // === MESSAGE HANDLERS ===

    @Bean
    @ServiceActivator(inputChannel = "carStatusChannel")
    public MessageHandler carStatusHandler() {
        return message -> {
            String payload = (String) message.getPayload();
            String topic = (String) message
                    .getHeaders()
                    .get("mqtt_receivedTopic");
            String carId = extractCarIdFromTopic(topic);

            System.out.println("=== CAR STATUS UPDATE ===");
            System.out.println("Car ID: " + carId);
            System.out.println("Topic: " + topic);
            System.out.println("Payload: " + payload);

            try {
                // Try to parse as JSON first
                IncomingVehicleStatusData statusData = objectMapper.readValue(payload, IncomingVehicleStatusData.class);

                // Use car_id from payload if provided, otherwise use extracted from topic
                String effectiveCarId = statusData.getCarId() != null ? statusData.getCarId() : carId;
                String effectiveStatus = statusData.getStatus() != null ? statusData.getStatus() : "ONLINE";
                Long effectiveTimestamp = statusData.getTimestamp() != null ? statusData.getTimestamp()
                        : System.currentTimeMillis();

                // Log what data we received
                System.out.println("  - Status: " + effectiveStatus);
                System.out.println("  - Has Location: "
                        + (statusData.getCurrentLatitude() != null && statusData.getCurrentLongitude() != null));
                System.out.println("  - Has Speed: " + (statusData.getCurrentSpeed() != null));
                System.out.println("  - Has Accuracy: " + (statusData.getAccuracy() != null));
                System.out.println("  - Has Bearing: " + (statusData.getBearing() != null));

                // Update local vehicle registry
                // Consider both ONLINE and READY as active statuses
                boolean isOnline = "ONLINE".equalsIgnoreCase(effectiveStatus) ||
                        "READY".equalsIgnoreCase(effectiveStatus);
                vehicleRegistryService.updateVehicleStatus(
                        Long.valueOf(effectiveCarId),
                        isOnline,
                        effectiveTimestamp);

                // Create message for RabbitMQ (all fields are optional except status, car_id,
                // timestamp)
                VehicleLocationUpdateMessage locationMsg = new VehicleLocationUpdateMessage();
                locationMsg.setCarId(effectiveCarId);
                locationMsg.setStatus(effectiveStatus);
                locationMsg.setTimestamp(effectiveTimestamp);
                locationMsg.setCurrentLatitude(statusData.getCurrentLatitude()); // Can be null
                locationMsg.setCurrentLongitude(statusData.getCurrentLongitude()); // Can be null
                locationMsg.setCurrentSpeed(statusData.getCurrentSpeed()); // Can be null
                locationMsg.setAccuracy(statusData.getAccuracy()); // Can be null
                locationMsg.setBearing(statusData.getBearing()); // Can be null

                // Publish to RabbitMQ
                vehicleLocationPublisherService.publish(locationMsg);

                // Send GPS update to Naviga API if location data is available
                if (statusData.getCurrentLatitude() != null && statusData.getCurrentLongitude() != null) {
                    try {
                        navigaService.updateGps(
                                effectiveCarId,
                                statusData.getCurrentLatitude(),
                                statusData.getCurrentLongitude(),
                                statusData.getCurrentSpeed(),
                                statusData.getBearing(), // heading
                                statusData.getAccuracy(),
                                effectiveTimestamp);
                    } catch (Exception e) {
                        System.err.println("⚠️ Failed to update GPS in Naviga API: " + e.getMessage());
                        // Don't fail the main flow - continue processing
                    }
                }

            } catch (Exception e) {
                // If JSON parsing fails, treat as simple status update
                System.out.println("  - Unable to parse as JSON, treating as simple status: " + e.getMessage());

                // Update vehicle online status
                vehicleRegistryService.updateVehicleStatus(
                        Long.valueOf(carId),
                        true,
                        System.currentTimeMillis());

                // Publish simple ONLINE status to RabbitMQ
                vehicleLocationPublisherService.publishStatus(carId, "ONLINE", System.currentTimeMillis());
            }
        };
    }

    @Bean
    @ServiceActivator(inputChannel = "tripUpdatesChannel")
    public MessageHandler tripUpdatesHandler() {
        return message -> {
            String payload = (String) message.getPayload();
            String topic = (String) message
                    .getHeaders()
                    .get("mqtt_receivedTopic");

            System.out.println("🔍 MQTT Handler Debug:");
            System.out.println("  - Topic: " + topic);
            System.out.println("  - Payload length: " + (payload != null ? payload.length() : "null"));
            System.out.println("  - All headers: " + message.getHeaders());

            // Use TripReceiverService to process the trip event message
            tripReceiverService.processTripEventMessage(topic, payload);
        };
    }

    @Bean
    @ServiceActivator(inputChannel = "heartbeatChannel")
    public MessageHandler heartbeatHandler() {
        return message -> {
            String payload = (String) message.getPayload();
            String topic = (String) message
                    .getHeaders()
                    .get("mqtt_receivedTopic");
            String carId = extractCarIdFromTopic(topic);

            System.out.println("=== HEARTBEAT PONG ===");
            System.out.println("Car ID: " + carId);
            System.out.println("Response: " + payload);

            // Update vehicle status in registry
            vehicleRegistryService.updateVehicleStatus(
                    Long.valueOf(carId),
                    true,
                    System.currentTimeMillis());
        };
    }

    @Bean
    @ServiceActivator(inputChannel = "heartbeatInboundChannel")
    public MessageHandler vehicleHeartbeatHandler() {
        return message -> {
            String payload = (String) message.getPayload();
            String topic = (String) message
                    .getHeaders()
                    .get("mqtt_receivedTopic");
            String carId = extractCarIdFromTopic(topic);

            System.out.println("=== VEHICLE HEARTBEAT ===");
            System.out.println("Car ID: " + carId);
            System.out.println("Topic: " + topic);
            System.out.println("Payload: " + payload);

            try {
                // Try to parse as JSON first
                IncomingVehicleStatusData statusData = objectMapper.readValue(payload, IncomingVehicleStatusData.class);

                // Use car_id from payload if provided, otherwise use extracted from topic
                String effectiveCarId = statusData.getCarId() != null ? statusData.getCarId() : carId;
                String effectiveStatus = statusData.getStatus() != null ? statusData.getStatus() : "ONLINE";
                Long effectiveTimestamp = statusData.getTimestamp() != null ? statusData.getTimestamp()
                        : System.currentTimeMillis();

                // Log what data we received
                System.out.println("  - Status: " + effectiveStatus);
                System.out.println("  - Has Location: "
                        + (statusData.getCurrentLatitude() != null && statusData.getCurrentLongitude() != null));
                System.out.println("  - Has Speed: " + (statusData.getCurrentSpeed() != null));
                System.out.println("  - Has Accuracy: " + (statusData.getAccuracy() != null));
                System.out.println("  - Has Bearing: " + (statusData.getBearing() != null));

                // Update local vehicle registry
                // Consider both ONLINE and READY as active statuses
                boolean isOnline = "ONLINE".equalsIgnoreCase(effectiveStatus) ||
                        "READY".equalsIgnoreCase(effectiveStatus);
                vehicleRegistryService.updateVehicleStatus(
                        Long.valueOf(effectiveCarId),
                        isOnline,
                        effectiveTimestamp);

                // Create message for RabbitMQ (all fields are optional except status, car_id,
                // timestamp)
                VehicleLocationUpdateMessage locationMsg = new VehicleLocationUpdateMessage();
                locationMsg.setCarId(effectiveCarId);
                locationMsg.setStatus(effectiveStatus);
                locationMsg.setTimestamp(effectiveTimestamp);
                locationMsg.setCurrentLatitude(statusData.getCurrentLatitude()); // Can be null
                locationMsg.setCurrentLongitude(statusData.getCurrentLongitude()); // Can be null
                locationMsg.setCurrentSpeed(statusData.getCurrentSpeed()); // Can be null
                locationMsg.setAccuracy(statusData.getAccuracy()); // Can be null
                locationMsg.setBearing(statusData.getBearing()); // Can be null

                // Publish to RabbitMQ
                vehicleLocationPublisherService.publish(locationMsg);

                // Send GPS update to Naviga API if location data is available
                if (statusData.getCurrentLatitude() != null && statusData.getCurrentLongitude() != null) {
                    try {
                        navigaService.updateGps(
                                effectiveCarId,
                                statusData.getCurrentLatitude(),
                                statusData.getCurrentLongitude(),
                                statusData.getCurrentSpeed(),
                                statusData.getBearing(), // heading
                                statusData.getAccuracy(),
                                effectiveTimestamp);
                    } catch (Exception e) {
                        System.err.println("⚠️ Failed to update GPS in Naviga API: " + e.getMessage());
                        // Don't fail the main flow - continue processing
                    }
                }

            } catch (Exception e) {
                // If JSON parsing fails, treat as simple heartbeat (always ONLINE)
                System.out.println("  - Unable to parse as JSON, treating as simple heartbeat: " + e.getMessage());

                // Update vehicle status in registry to mark as online
                vehicleRegistryService.updateVehicleStatus(
                        Long.valueOf(carId),
                        true,
                        System.currentTimeMillis());

                // Publish simple ONLINE status to RabbitMQ
                vehicleLocationPublisherService.publishStatus(carId, "ONLINE", System.currentTimeMillis());
            }
        };
    }

    @Bean
    @ServiceActivator(inputChannel = "bookingBundleChannel")
    public MessageHandler bookingBundleHandler() {
        return message -> {
            String payload = (String) message.getPayload();
            String topic = (String) message.getHeaders().get("mqtt_receivedTopic");

            // Guard against processing our own outbound publishes
            if (topic != null && topic.contains("/booking_bundle/outbound")) {
                return;
            }

            System.out.println("📦 === RECEIVING BOOKING BUNDLE FROM MQTT ===");
            System.out.println("  - Topic: " + topic);
            System.out.println("  - Payload length: " + (payload != null ? payload.length() : "null"));
            System.out.println("  - Timestamp: " + System.currentTimeMillis());

            try {
                // Log the raw payload for debugging
                System.out.println("  - Raw payload: " + payload);

                BookingBundle bundle = objectMapper.readValue(payload, BookingBundle.class);
                System.out.println("✅ Successfully deserialized booking bundle:");
                System.out.println("  - Trip ID: " + (bundle != null ? bundle.tripId : "null"));
                System.out.println(
                        "  - Booking ID: " + (bundle != null && bundle.booking != null ? bundle.booking.id : "null"));

                // Publish to RabbitMQ
                bookingBundlePublisherService.publish(bundle);
                System.out.println("✅ Successfully forwarded booking bundle to RabbitMQ");

            } catch (Exception e) {
                System.err.println("❌ FAILED to process booking bundle from MQTT:");
                System.err.println("  - Error: " + e.getMessage());
                System.err.println("  - Topic: " + topic);
                System.err.println("  - Payload: " + payload);
                e.printStackTrace();
            }
        };
    }

    @Bean
    @ServiceActivator(inputChannel = "locationBatchChannel")
    public MessageHandler locationBatchHandler() {
        return message -> {
            byte[] payload = (byte[]) message.getPayload();
            String topic = (String) message.getHeaders().get("mqtt_receivedTopic");

            // Delegate to location listener service for processing
            locationListenerService.processLocationBatch(topic, payload);
        };
    }

    // === OUTBOUND HANDLERS ===

    @Bean
    @ServiceActivator(inputChannel = "tripAssignmentChannel")
    public MessageHandler tripAssignmentOutbound() {
        MqttPahoMessageHandler messageHandler = new MqttPahoMessageHandler(
                brokerUrl,
                clientId + "-trip-assign-" + System.currentTimeMillis(),
                mqttClientFactory());
        messageHandler.setAsync(true);
        messageHandler.setDefaultQos(1);
        messageHandler.setDefaultRetained(true); // Retain trip assignments
        return messageHandler;
    }

    @Bean
    @ServiceActivator(inputChannel = "bookingUpdatesChannel")
    public MessageHandler bookingUpdatesOutbound() {
        MqttPahoMessageHandler messageHandler = new MqttPahoMessageHandler(
                brokerUrl,
                clientId + "-bookings-" + System.currentTimeMillis(),
                mqttClientFactory());
        messageHandler.setAsync(true);
        messageHandler.setDefaultQos(1);
        messageHandler.setDefaultRetained(false); // Don't retain booking updates
        return messageHandler;
    }

    @Bean
    @ServiceActivator(inputChannel = "heartbeatOutboundChannel")
    public MessageHandler heartbeatOutbound() {
        MqttPahoMessageHandler messageHandler = new MqttPahoMessageHandler(
                brokerUrl,
                clientId + "-ping-" + System.currentTimeMillis(),
                mqttClientFactory());
        messageHandler.setAsync(true);
        messageHandler.setDefaultQos(1);
        messageHandler.setDefaultRetained(false);
        return messageHandler;
    }

    @Bean
    @ServiceActivator(inputChannel = "bookingBundleOutboundChannel")
    public MessageHandler bookingBundleOutbound() {
        MqttPahoMessageHandler messageHandler = new MqttPahoMessageHandler(
                brokerUrl,
                clientId + "-bundle-out-" + System.currentTimeMillis(),
                mqttClientFactory());
        messageHandler.setAsync(true);
        messageHandler.setDefaultQos(1);
        messageHandler.setDefaultRetained(false);
        return messageHandler;
    }

    @Bean
    @ServiceActivator(inputChannel = "vehicleSettingsOutboundChannel")
    public MessageHandler vehicleSettingsOutbound() {
        MqttPahoMessageHandler messageHandler = new MqttPahoMessageHandler(
                brokerUrl,
                clientId + "-settings-out-" + System.currentTimeMillis(),
                mqttClientFactory());
        messageHandler.setAsync(true);
        messageHandler.setDefaultQos(1);
        messageHandler.setDefaultRetained(true); // Retain settings so vehicle gets them on reconnect
        return messageHandler;
    }

    // === UTILITY METHODS ===

    private String extractCarIdFromTopic(String topic) {
        if (topic != null && topic.startsWith("car/")) {
            String[] parts = topic.split("/");
            if (parts.length >= 2) {
                return parts[1];
            }
        }
        return "unknown";
    }
}
