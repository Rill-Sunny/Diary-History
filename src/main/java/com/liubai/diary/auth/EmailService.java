package com.liubai.diary.auth;

import com.liubai.diary.config.ApiException;
import org.springframework.core.env.Environment;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Service
public class EmailService {
    private final Environment environment;
    private final org.springframework.beans.factory.ObjectProvider<JavaMailSender> sender;

    public EmailService(Environment environment, org.springframework.beans.factory.ObjectProvider<JavaMailSender> sender) {
        this.environment = environment;
        this.sender = sender;
    }

    public boolean isConfigured() {
        String host=environment.getProperty("spring.mail.host");
        return host != null && !host.isBlank() && sender.getIfAvailable() != null;
    }

    public void sendVerification(String email, String rawToken) {
        send(email, "验证你的留白日记邮箱", "请打开此链接完成验证：\n" + baseUrl() + "/?verify=" +
                URLEncoder.encode(rawToken, StandardCharsets.UTF_8) + "\n\n此链接 30 分钟内有效，只能使用一次。");
    }

    public void sendPasswordReset(String email, String rawToken) {
        send(email, "重置留白日记密码", "请打开此链接设置新密码：\n" + baseUrl() + "/?reset=" +
                URLEncoder.encode(rawToken, StandardCharsets.UTF_8) + "\n\n此链接 60 分钟内有效，只能使用一次。");
    }

    public void sendEmailChange(String email, String rawToken) {
        send(email, "确认留白日记的新邮箱", "请打开此链接确认新邮箱：\n" + baseUrl() + "/?changeEmail=" +
                URLEncoder.encode(rawToken, StandardCharsets.UTF_8) + "\n\n此链接 30 分钟内有效，只能使用一次。");
    }

    public void sendWritingReminder(String email) {
        send(email, "留白日记 · 写作提醒", "留一点时间给今天。打开留白日记记录今天：\n" + baseUrl());
    }

    private void send(String email, String subject, String body) {
        JavaMailSender mailSender = sender.getIfAvailable();
        String host=environment.getProperty("spring.mail.host");
        if (mailSender == null || host == null || host.isBlank()) {
            throw ApiException.unavailable("邮件服务未配置，邮件尚未发送。");
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(environment.getProperty("app.mail.from", "no-reply@localhost"));
        message.setTo(email);
        message.setSubject(subject);
        message.setText(body);
        try {
            mailSender.send(message);
        } catch (RuntimeException exception) {
            throw ApiException.unavailable("邮件服务暂时不可用，邮件尚未发送。");
        }
    }

    private String baseUrl() {
        return environment.getProperty("app.base-url",
                environment.getProperty("APP_BASE_URL", "http://localhost:5173"));
    }
}
