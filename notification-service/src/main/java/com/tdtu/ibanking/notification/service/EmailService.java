package com.tdtu.ibanking.notification.service;

import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender mailSender;
    private final SpringTemplateEngine templateEngine;

    public void sendTemplatedEmail(String to, String type, Map<String, String> variables) {
        try {
            String templateName;
            String subject;

            switch (type) {
                case "OTP" -> {
                    templateName = "emails/otp-email";
                    subject = "Mã OTP xác thực thanh toán học phí";
                }
                case "PAYMENT_SUCCESS" -> {
                    templateName = "emails/payment-success-email";
                    subject = "Thanh toán học phí thành công";
                }
                default -> throw new IllegalArgumentException("Loại email không xác định: " + type);
            }

            Context context = new Context();
            if (variables != null) {
                variables.forEach(context::setVariable);
            }
            String html = templateEngine.process(templateName, context);

            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, false, "UTF-8");
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);   // true = nội dung là HTML

            mailSender.send(mimeMessage);
            log.info("Email loại {} đã gửi tới {}", type, to);
        } catch (Exception e) {
            log.error("Gửi email thất bại tới {}: {}", to, e.getMessage());
            throw new RuntimeException("Email sending failed", e);
        }
    }
}