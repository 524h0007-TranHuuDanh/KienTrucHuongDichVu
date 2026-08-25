package com.tdtu.ibanking.payment.config;

import com.tdtu.ibanking.payment.dto.EmailMessage;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;

import java.util.Map;

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
    public MessageConverter messageConverter() {
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter();
        DefaultJackson2JavaTypeMapper typeMapper = new DefaultJackson2JavaTypeMapper();
        typeMapper.setIdClassMapping(Map.of("emailMessage", EmailMessage.class));
        converter.setJavaTypeMapper(typeMapper);
        return converter;
    }
}
