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