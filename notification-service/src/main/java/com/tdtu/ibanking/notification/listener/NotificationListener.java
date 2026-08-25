package com.tdtu.ibanking.notification.listener;

import com.tdtu.ibanking.notification.model.EmailMessage;   // Import đúng model
import com.tdtu.ibanking.notification.service.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;   // ← "rabbit" (2 chữ b), không phải "rabiit"
import org.springframework.stereotype.Component;

@Component
@Slf4j   // ← Tạo biến log
@RequiredArgsConstructor
public class NotificationListener {

    private final EmailService emailService;

    @RabbitListener(queues = "email_queue")   // ← Lắng nghe queue này
    public void handleEmailNotification(EmailMessage message) {
        log.info("Received email for: {}", message.getTo());   // ← Dùng log
        emailService.sendEmail(message.getTo(), message.getSubject(), message.getBody());
        log.info("Email sent to: {}", message.getTo());
    }
}