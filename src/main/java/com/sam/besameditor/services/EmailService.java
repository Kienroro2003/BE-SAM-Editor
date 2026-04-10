package com.sam.besameditor.services;

import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
public class EmailService {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String fromEmail;

    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    public void sendOtpEmail(String toEmail, String otpCode) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromEmail);
            helper.setTo(toEmail);
            helper.setSubject("SAM Editor - Email Verification Code");
            helper.setText(buildOtpHtml(otpCode), true);
            mailSender.send(message);
        } catch (Exception e) {
            throw new RuntimeException("Failed to send OTP email: " + e.getMessage());
        }
    }

    private String buildOtpHtml(String otpCode) {
        return """
                <div style="font-family: Arial, sans-serif; max-width: 480px; margin: 0 auto; padding: 32px; background: #1e1e1e; border-radius: 8px;">
                  <h2 style="color: #4fc3f7; margin-bottom: 8px;">SAM Editor</h2>
                  <p style="color: #ccc; font-size: 14px;">Your email verification code:</p>
                  <div style="background: #2d2d2d; border-radius: 6px; padding: 20px; text-align: center; margin: 20px 0;">
                    <span style="font-size: 36px; font-weight: bold; letter-spacing: 10px; color: #fff;">%s</span>
                  </div>
                  <p style="color: #888; font-size: 12px;">This code expires in <strong style="color:#f39c12">5 minutes</strong>. Do not share it with anyone.</p>
                </div>
                """.formatted(otpCode);
    }
}
