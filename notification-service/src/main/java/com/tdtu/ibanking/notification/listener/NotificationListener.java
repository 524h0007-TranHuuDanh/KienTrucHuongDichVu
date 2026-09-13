package com.tdtu.ibanking.notification.listener;

import com.tdtu.ibanking.notification.model.EmailMessage;
import com.tdtu.ibanking.notification.service.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class NotificationListener {

    private final EmailService emailService;

    @RabbitListener(queues = "email_queue")
    public void handleEmailNotification(EmailMessage message) {
        log.info("Received email for: {} (type={})", message.getTo(), message.getType());
        emailService.sendTemplatedEmail(message.getTo(), message.getType(), message.getVariables());
        log.info("Email sent to: {}", message.getTo());
    }
}