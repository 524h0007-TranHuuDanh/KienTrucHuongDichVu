package com.tdtu.ibanking.notification.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
//p07
@Configuration
public class RabbitMQConfig {

    private static final String EMAIL_QUEUE = "email_queue";
    private static final String EMAIL_DLX = "email_dlx";
    private static final String EMAIL_DLQ = "email_queue.dlq";

    @Bean
    public Queue emailQueue() {
        return QueueBuilder.durable(EMAIL_QUEUE)
                .deadLetterExchange(EMAIL_DLX)
                .deadLetterRoutingKey(EMAIL_DLQ)
                .build();
    }

    @Bean
    public DirectExchange emailDeadLetterExchange() {
        return new DirectExchange(EMAIL_DLX);
    }

    @Bean
    public Queue emailDeadLetterQueue() {
        return QueueBuilder.durable(EMAIL_DLQ).build();
    }

    @Bean
    public Binding emailDeadLetterBinding() {
        return BindingBuilder.bind(emailDeadLetterQueue())
                .to(emailDeadLetterExchange())
                .with(EMAIL_DLQ);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}