package com.nexxserve.cavgomqt.config;

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

    public static final String BOOKINGS_QUEUE = "bookings.queue";
    public static final String TRIPS_QUEUE = "trips.queue";
    public static final String TRIPS_PUBLISHER_QUEUE = "trips.publisher.queue";
    public static final String BOOKINGS_BUNDLE_QUEUE = "bookingbundles.queue";
    public static final String BOOKINGS_BUNDLE_REPLY_QUEUE = "bookingbundles.reply.queue";
    public static final String VEHICLE_LOCATION_UPDATES_QUEUE = "vehicle.location.updates";
    public static final String VEHICLE_SETTINGS_QUEUE = "vehicle.settings.queue";
    public static final String VEHICLE_SETTINGS_EXCHANGE = "vehicle.settings.exchange";

    @Bean
    public FanoutExchange bookingsFanoutExchange() {
        return new FanoutExchange("bookings.fanout");
    }

    @Bean
    public Binding bookingsQueueBinding(Queue bookingsQueue, FanoutExchange bookingsFanoutExchange) {
        return BindingBuilder.bind(bookingsQueue).to(bookingsFanoutExchange);
    }

    @Bean
    public Queue bookingsQueue() {
        return QueueBuilder.durable(BOOKINGS_QUEUE)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", BOOKINGS_QUEUE + ".dlq")
                .build();
    }

    @Bean
    public Queue tripsQueue() {
        return QueueBuilder.durable(TRIPS_QUEUE)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", TRIPS_QUEUE + ".dlq")
                .build();
    }

    @Bean
    public Queue tripsPublisherQueue() {
        return QueueBuilder.durable(TRIPS_PUBLISHER_QUEUE)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", TRIPS_PUBLISHER_QUEUE + ".dlq")
                .build();
    }

    @Bean
    public Queue bookingsDeadLetterQueue() {
        return QueueBuilder.durable(BOOKINGS_QUEUE + ".dlq").build();
    }

    @Bean
    public Queue tripsDeadLetterQueue() {
        return QueueBuilder.durable(TRIPS_QUEUE + ".dlq").build();
    }

    @Bean
    public Queue tripsPublisherDeadLetterQueue() {
        return QueueBuilder.durable(TRIPS_PUBLISHER_QUEUE + ".dlq").build();
    }

    @Bean
    public Queue bookingsBundleQueue() {
        return QueueBuilder.durable(BOOKINGS_BUNDLE_QUEUE)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", BOOKINGS_BUNDLE_QUEUE + ".dlq")
                .build();
    }

    @Bean
    public Queue bookingsBundleDeadLetterQueue() {
        return QueueBuilder.durable(BOOKINGS_BUNDLE_QUEUE + ".dlq").build();
    }

    @Bean
    public Queue bookingsBundleReplyQueue() {
        return QueueBuilder.durable(BOOKINGS_BUNDLE_REPLY_QUEUE)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", BOOKINGS_BUNDLE_REPLY_QUEUE + ".dlq")
                .build();
    }

    @Bean
    public Queue bookingsBundleReplyDeadLetterQueue() {
        return QueueBuilder.durable(BOOKINGS_BUNDLE_REPLY_QUEUE + ".dlq").build();
    }

    @Bean
    public Queue vehicleLocationUpdatesQueue() {
        // Queue already exists without DLQ in RabbitMQ (created by another service)
        // Just declare it as durable without additional arguments to avoid mismatch
        return QueueBuilder.durable(VEHICLE_LOCATION_UPDATES_QUEUE).build();
    }

    @Bean
    public TopicExchange vehicleSettingsExchange() {
        return new TopicExchange(VEHICLE_SETTINGS_EXCHANGE);
    }

    @Bean
    public Queue vehicleSettingsQueue() {
        return QueueBuilder.durable(VEHICLE_SETTINGS_QUEUE)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", VEHICLE_SETTINGS_QUEUE + ".dlq")
                .build();
    }

    @Bean
    public Queue vehicleSettingsDeadLetterQueue() {
        return QueueBuilder.durable(VEHICLE_SETTINGS_QUEUE + ".dlq").build();
    }

    @Bean
    public Binding vehicleSettingsBinding(Queue vehicleSettingsQueue, TopicExchange vehicleSettingsExchange) {
        // Bind with wildcard to receive settings for all vehicles
        return BindingBuilder.bind(vehicleSettingsQueue)
                .to(vehicleSettingsExchange)
                .with("vehicle.settings.*");
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter());
        return template;
    }

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