package com.liubai.diary.reminder;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liubai.diary.auth.AppUser;
import com.liubai.diary.auth.EmailService;
import com.liubai.diary.auth.UserMapper;
import com.liubai.diary.config.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;

@Service
public class ReminderService {
    private static final Settings DEFAULT = new Settings(false, false, "20:30", "WEEKDAYS", List.of());
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final EmailService mail;
    private final WebPushService push;
    private final UserMapper users;

    public ReminderService(JdbcTemplate jdbc, ObjectMapper json, EmailService mail, WebPushService push, UserMapper users) {
        this.jdbc = jdbc;
        this.json = json;
        this.mail = mail;
        this.push = push;
        this.users = users;
    }

    public record Schedule(String time, String type, List<Integer> weekdays) {}
    public record Settings(boolean emailEnabled, boolean pushEnabled, String time, String type, List<Integer> weekdays) {}
    public record Delivery(LocalDate localDate, String status, String message) {}
    public record Status(Settings settings, boolean mailConfigured, String pushConfiguration, String pushPublicKey,
                         List<String> subscribedEndpoints, Delivery lastEmail, Delivery lastPush) {}
    public record Subscription(String endpoint, String p256dh, String auth) {}

    Settings settings(long owner) {
        List<Settings> saved = jdbc.query("""
                SELECT email_enabled, push_enabled, TIME_FORMAT(reminder_time, '%H:%i'), schedule_type, weekdays_json
                FROM reminder_settings WHERE owner_id=?
                """, (rs, row) -> new Settings(rs.getBoolean(1), rs.getBoolean(2), rs.getString(3),
                rs.getString(4), parseDays(rs.getString(5))), owner);
        return saved.isEmpty() ? DEFAULT : saved.get(0);
    }

    public Schedule schedule(long owner) {
        Settings settings = settings(owner);
        return new Schedule(settings.time(), settings.type(), settings.weekdays());
    }

    @Transactional
    public void restoreSchedule(long owner, Schedule schedule) {
        lockOwner(owner);
        Settings current = settings(owner);
        storeSettings(owner, new Settings(current.emailEnabled(), current.pushEnabled(),
                schedule.time(), schedule.type(), schedule.weekdays()));
    }

    Status status(long owner) {
        List<String> endpoints = jdbc.queryForList(
                "SELECT endpoint_hash FROM push_subscriptions WHERE owner_id=? AND revoked_at IS NULL ORDER BY id",
                String.class, owner);
        return new Status(settings(owner), mail.isConfigured(), push.configuration(), push.publicKey(), endpoints,
                delivery(owner, "EMAIL"), delivery(owner, "PUSH"));
    }

    @Transactional
    public Status update(long owner, Settings input) {
        lockOwner(owner);
        storeSettings(owner, input);
        return status(owner);
    }

    private void storeSettings(long owner, Settings input) {
        if (input.time() == null || !input.time().matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]"))
            throw ApiException.badRequest("请输入有效的提醒时间。");
        if (input.type() == null || !List.of("DAILY", "WEEKDAYS", "CUSTOM").contains(input.type()))
            throw ApiException.badRequest("请选择有效的提醒频率。");
        List<Integer> days = List.of();
        if ("CUSTOM".equals(input.type())) {
            if (input.weekdays() == null || input.weekdays().isEmpty() || input.weekdays().size() > 7
                    || input.weekdays().stream().anyMatch(day -> day == null || day < 1 || day > 7))
                throw ApiException.badRequest("请选择至少一个有效的提醒星期。");
            days = input.weekdays().stream().distinct().sorted().toList();
        }
        jdbc.update("""
                INSERT INTO reminder_settings (owner_id, email_enabled, push_enabled, reminder_time, schedule_type, weekdays_json)
                VALUES (?, ?, ?, ?, ?, ?) AS new_settings
                ON DUPLICATE KEY UPDATE email_enabled=new_settings.email_enabled, push_enabled=new_settings.push_enabled,
                    reminder_time=new_settings.reminder_time, schedule_type=new_settings.schedule_type,
                    weekdays_json=new_settings.weekdays_json
                """, owner, input.emailEnabled(), input.pushEnabled(), input.time() + ":00", input.type(), days.toString());
    }

    @Transactional
    public Status subscribe(long owner, Subscription subscription) {
        if (push.publicKey() == null) throw ApiException.unavailable("服务器 Web Push 未完成配置。");
        push.validateSubscription(subscription.endpoint(), subscription.p256dh(), subscription.auth());
        lockOwner(owner);
        String hash = endpointHash(subscription.endpoint());
        Integer otherDevices = jdbc.queryForObject("""
                SELECT COUNT(*) FROM push_subscriptions WHERE owner_id=? AND revoked_at IS NULL AND endpoint_hash<>?
                """, Integer.class, owner, hash);
        if (otherDevices != null && otherDevices >= 10) throw ApiException.conflict("每个账号最多订阅 10 台浏览器。");
        jdbc.update("""
                UPDATE push_subscriptions SET revoked_at=CURRENT_TIMESTAMP(3)
                WHERE owner_id<>? AND endpoint_hash=? AND auth_secret=? AND p256dh=? AND revoked_at IS NULL
                """, owner, hash, subscription.auth(), subscription.p256dh());
        jdbc.update("""
                INSERT INTO push_subscriptions (owner_id, endpoint_hash, endpoint, p256dh, auth_secret)
                VALUES (?, ?, ?, ?, ?) AS new_subscription
                ON DUPLICATE KEY UPDATE endpoint=new_subscription.endpoint, p256dh=new_subscription.p256dh,
                    auth_secret=new_subscription.auth_secret, revoked_at=NULL
                """, owner, hash, subscription.endpoint(), subscription.p256dh(), subscription.auth());
        return status(owner);
    }

    @Transactional
    public Status unsubscribe(long owner, String endpoint) {
        lockOwner(owner);
        revoke(owner, endpoint);
        return status(owner);
    }

    void revoke(long owner, String endpoint) {
        jdbc.update("""
                UPDATE push_subscriptions SET revoked_at=CURRENT_TIMESTAMP(3)
                WHERE owner_id=? AND endpoint_hash=? AND revoked_at IS NULL
                """, owner, endpointHash(endpoint));
    }

    List<Subscription> subscriptions(long owner) {
        return jdbc.query("""
                SELECT endpoint, p256dh, auth_secret FROM push_subscriptions WHERE owner_id=? AND revoked_at IS NULL ORDER BY id
                """, (rs, row) -> new Subscription(rs.getString(1), rs.getString(2), rs.getString(3)), owner);
    }

    private Delivery delivery(long owner, String channel) {
        RowMapper<Delivery> rowMapper = (rs, row) -> new Delivery(
                rs.getObject(1, LocalDate.class), rs.getString(2), rs.getString(3));
        return jdbc.query("""
                SELECT local_date, status, last_error FROM reminder_sends
                WHERE owner_id=? AND channel=? ORDER BY id DESC LIMIT 1
                """, rowMapper, owner, channel).stream().findFirst().orElse(null);
    }

    private void lockOwner(long owner) {
        AppUser user = users.lockById(owner);
        if (user == null || !user.isEnabled()) throw ApiException.notFound();
    }

    private List<Integer> parseDays(String value) {
        try {
            return json.readValue(value, new TypeReference<List<Integer>>() {});
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("Invalid stored reminder weekdays", exception);
        }
    }

    private static String endpointHash(String endpoint) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(endpoint.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
