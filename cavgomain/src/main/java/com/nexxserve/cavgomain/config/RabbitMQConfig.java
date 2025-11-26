package com.nexxserve.cavgomain.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableRabbit
public class RabbitMQConfig {

    // Queue names
    public static final String VEHICLE_LOCATION_QUEUE = "vehicle.location.updates";
    
    // Exchange for settings
    public static final String VEHICLE_SETTINGS_EXCHANGE = "vehicle.settings.exchange";
    public static final String VEHICLE_SETTINGS_ROUTING_KEY_PREFIX = "vehicle.settings.";

    // Bean for combined location/status queue
    @Bean
    public Queue vehicleLocationQueue() {
        return new Queue(VEHICLE_LOCATION_QUEUE, true);
    }

    // Bean for settings exchange (topic exchange for flexible routing)
    @Bean
    public TopicExchange vehicleSettingsExchange() {
        return new TopicExchange(VEHICLE_SETTINGS_EXCHANGE);
    }

    // Message converter for JSON
    @Bean
    public MessageConverter jsonMessageConverter() {
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter();
        // Don't use __TypeId__ header - infer type from method signature instead
        converter.setAlwaysConvertToInferredType(true);
        return converter;
    }

    // RabbitTemplate with JSON converter
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter());
        return template;
    }

    // Listener container factory with JSON converter
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter());
        factory.setConcurrentConsumers(3);
        factory.setMaxConcurrentConsumers(10);
        factory.setPrefetchCount(5);
        return factory;
    }
}

