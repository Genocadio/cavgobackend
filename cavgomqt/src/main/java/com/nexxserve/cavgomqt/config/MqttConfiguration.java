package com.nexxserve.cavgomqt.config;

import com.nexxserve.cavgomqt.service.VehicleRegistryService;
import com.nexxserve.cavgomqt.service.TripReceiverService;
import com.nexxserve.cavgomqt.service.RabbitMQBookingBundlePublisherService;
import com.nexxserve.cavgomqt.dto.mqtt.BookingBundle;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

@Configuration
public class MqttConfiguration {

    @Autowired
    private VehicleRegistryService vehicleRegistryService;

    @Autowired
    private TripReceiverService tripReceiverService;

    @Autowired
    private RabbitMQBookingBundlePublisherService bookingBundlePublisherService;

    @Autowired
    private ObjectMapper objectMapper;

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
        DefaultMqttPahoClientFactory factory =
            new DefaultMqttPahoClientFactory();
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

        // Last Will and Testament for backend service
        String lwillTopic = "backend/status";
        String lwillMessage =
            "{\"status\":\"OFFLINE\",\"timestamp\":" +
            System.currentTimeMillis() +
            "}";
        options.setWill(lwillTopic, lwillMessage.getBytes(), 1, true);

        factory.setConnectionOptions(options);
        return factory;
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
    public MessageChannel bookingBundleChannel() {
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

    // === INBOUND ADAPTERS ===

    @Bean
    public MessageProducer carStatusInbound() {
        MqttPahoMessageDrivenChannelAdapter adapter =
            new MqttPahoMessageDrivenChannelAdapter(
                brokerUrl,
                clientId + "-status-" + System.currentTimeMillis(),
                mqttClientFactory(),
                "car/+/status"
            );
        adapter.setCompletionTimeout(10000); // Increased timeout for SSL
        adapter.setConverter(new DefaultPahoMessageConverter());
        adapter.setQos(1);
        adapter.setOutputChannel(carStatusChannel());
        return adapter;
    }

    @Bean
    public MessageProducer tripUpdatesInbound() {
        MqttPahoMessageDrivenChannelAdapter adapter =
            new MqttPahoMessageDrivenChannelAdapter(
                brokerUrl,
                clientId + "-trip-updates-" + System.currentTimeMillis(),
                mqttClientFactory(),
                "car/+/trip/updates"
            );
        adapter.setCompletionTimeout(10000); // Increased timeout for SSL
        adapter.setConverter(new DefaultPahoMessageConverter());
        adapter.setQos(1);
        adapter.setOutputChannel(tripUpdatesChannel());
        return adapter;
    }

    @Bean
    public MessageProducer heartbeatInbound() {
        MqttPahoMessageDrivenChannelAdapter adapter =
            new MqttPahoMessageDrivenChannelAdapter(
                brokerUrl,
                clientId + "-heartbeat-" + System.currentTimeMillis(),
                mqttClientFactory(),
                "car/+/pong"
            );
        adapter.setCompletionTimeout(10000); // Increased timeout for SSL
        adapter.setConverter(new DefaultPahoMessageConverter());
        adapter.setQos(1);
        adapter.setOutputChannel(heartbeatChannel());
        return adapter;
    }

    @Bean
    public MessageProducer bookingBundleInbound() {
        MqttPahoMessageDrivenChannelAdapter adapter =
            new MqttPahoMessageDrivenChannelAdapter(
                brokerUrl,
                clientId + "-booking-bundle-" + System.currentTimeMillis(),
                mqttClientFactory(),
                "trip/+/booking_bundle"
            );
        adapter.setCompletionTimeout(10000);
        adapter.setConverter(new DefaultPahoMessageConverter());
        adapter.setQos(1);
        adapter.setOutputChannel(bookingBundleChannel());
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
            Long carId = Long.valueOf(extractCarIdFromTopic(topic));

            System.out.println("=== CAR STATUS UPDATE ===");
            System.out.println("Car ID: " + carId);
            System.out.println("Status: " + payload);

            // Update vehicle online status
            vehicleRegistryService.updateVehicleStatus(
                carId,
                true,
                System.currentTimeMillis()
            );
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
                System.currentTimeMillis()
            );
        };
    }

    @Bean
    @ServiceActivator(inputChannel = "bookingBundleChannel")
    public MessageHandler bookingBundleHandler() {
        return message -> {
            String payload = (String) message.getPayload();
            String topic = (String) message.getHeaders().get("mqtt_receivedTopic");
            
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
                System.out.println("  - Booking ID: " + (bundle != null && bundle.booking != null ? bundle.booking.id : "null"));
                
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

    // === OUTBOUND HANDLERS ===

    @Bean
    @ServiceActivator(inputChannel = "tripAssignmentChannel")
    public MessageHandler tripAssignmentOutbound() {
        MqttPahoMessageHandler messageHandler = new MqttPahoMessageHandler(
            brokerUrl,
            clientId + "-trip-assign-" + System.currentTimeMillis(),
            mqttClientFactory()
        );
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
            mqttClientFactory()
        );
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
            mqttClientFactory()
        );
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
            mqttClientFactory()
        );
        messageHandler.setAsync(true);
        messageHandler.setDefaultQos(1);
        messageHandler.setDefaultRetained(false);
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
