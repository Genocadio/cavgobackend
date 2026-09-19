package com.gocavgo.delivary.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.RabbitListenerContainerFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {

    @Value("${rabbitmq.naviga.exchange}")
    private String navigaExchangeName;

    @Value("${rabbitmq.naviga.queue}")
    private String navigaQueueName;

    @Value("${rabbitmq.cavgotrips.exchange}")
    private String cavgoTripsExchangeName;

    @Value("${rabbitmq.cavgotrips.queue}")
    private String cavgoTripsQueueName;

    @Bean
    public FanoutExchange navigaTripExchange() {
        return new FanoutExchange(navigaExchangeName, true, false);
    }

    @Bean
    public Queue navigaTripQueue() {
        return new Queue(navigaQueueName, true, false, false);
    }

    @Bean
    public Binding navigaTripBinding(Queue navigaTripQueue, FanoutExchange navigaTripExchange) {
        return BindingBuilder.bind(navigaTripQueue).to(navigaTripExchange);
    }

    @Bean
    public FanoutExchange cavgoTripsExchange() {
        return new FanoutExchange(cavgoTripsExchangeName, true, false);
    }

    @Bean
    public Queue cavgoTripsQueue() {
        return new Queue(cavgoTripsQueueName, true, false, false);
    }

    @Bean
    public Binding cavgoTripsBinding(Queue cavgoTripsQueue, FanoutExchange cavgoTripsExchange) {
        return BindingBuilder.bind(cavgoTripsQueue).to(cavgoTripsExchange);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * Listener container factory that deserialises incoming JSON into Map<String, Object>
     * (the default factory uses SimpleMessageConverter which gives raw byte[]).
     */
    @Bean
    public RabbitListenerContainerFactory<?> rabbitListenerContainerFactory(ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter());
        return factory;
    }
}
