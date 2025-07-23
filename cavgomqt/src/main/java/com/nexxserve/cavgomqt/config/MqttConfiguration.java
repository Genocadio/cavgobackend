package com.nexxserve.cavgomqt.config;

import com.nexxserve.cavgomqt.service.VehicleRegistryService;
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
        options.setCleanSession(false); // Persistent session for reliable delivery
        options.setConnectionTimeout(30);
        options.setKeepAliveInterval(60);
        options.setAutomaticReconnect(true);
        options.setMaxInflight(100); // Allow more concurrent messages

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

    // === INBOUND ADAPTERS ===

    @Bean
    public MessageProducer carStatusInbound() {
        MqttPahoMessageDrivenChannelAdapter adapter =
            new MqttPahoMessageDrivenChannelAdapter(
                brokerUrl,
                clientId + "-status",
                mqttClientFactory(),
                "car/+/status"
            );
        adapter.setCompletionTimeout(5000);
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
                clientId + "-trip-updates",
                mqttClientFactory(),
                "car/+/trip/updates"
            );
        adapter.setCompletionTimeout(5000);
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
                clientId + "-heartbeat",
                mqttClientFactory(),
                "car/+/pong"
            );
        adapter.setCompletionTimeout(5000);
        adapter.setConverter(new DefaultPahoMessageConverter());
        adapter.setQos(1);
        adapter.setOutputChannel(heartbeatChannel());
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

            String carId = extractCarIdFromTopic(topic);

            System.out.println("=== TRIP UPDATE ===");
            System.out.println("Car ID: " + carId);
            System.out.println("Update: " + payload);

            // TODO: Process trip update (update trip status, notify passengers)
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

    // === OUTBOUND HANDLERS ===

    @Bean
    @ServiceActivator(inputChannel = "tripAssignmentChannel")
    public MessageHandler tripAssignmentOutbound() {
        MqttPahoMessageHandler messageHandler = new MqttPahoMessageHandler(
            brokerUrl,
            clientId + "-trip-assign",
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
            clientId + "-bookings",
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
            clientId + "-ping",
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
