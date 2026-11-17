package com.liubai.diary.reminder;

import com.liubai.diary.auth.AppUser;
import com.liubai.diary.auth.EmailService;
import com.liubai.diary.auth.UserMapper;
import com.liubai.diary.config.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.*;
import java.util.List;

@Component
public class ReminderJob {
    private static final Logger LOG = LoggerFactory.getLogger(ReminderJob.class);
    private static final Duration DELIVERY_WINDOW = Duration.ofMinutes(30);
    private final JdbcTemplate jdbc;
    private final ReminderService reminders;
    private final EmailService mail;
    private final WebPushService push;
    private final UserMapper users;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public ReminderJob(JdbcTemplate jdbc, ReminderService reminders, EmailService mail, WebPushService push,
                       UserMapper users, PlatformTransactionManager transactions, Clock clock) {
        this.jdbc = jdbc;
        this.reminders = reminders;
        this.mail = mail;
        this.push = push;
        this.users = users;
        this.transaction = new TransactionTemplate(transactions);
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT1M")
    public void run() {
        jdbc.update("""
                UPDATE reminder_sends SET status='UNKNOWN', last_error='发送时服务中断，投递结果无法确认。'
                WHERE status='SENDING' AND started_at<DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 30 MINUTE)
                """);
        if (!mail.isConfigured() && push.publicKey() == null) return;
        List<Long> owners = jdbc.queryForList("""
                SELECT s.owner_id FROM reminder_settings s JOIN app_users u ON u.id=s.owner_id
                WHERE u.enabled=TRUE AND u.email_verified=TRUE AND (s.email_enabled=TRUE OR s.push_enabled=TRUE)
                """, Long.class);
        for (long owner : owners) {
            try {
                if (mail.isConfigured()) dispatch(owner, Channel.EMAIL);
                if (push.publicKey() != null) dispatch(owner, Channel.PUSH);
            } catch (RuntimeException exception) {
                LOG.warn("Reminder job failed for account {} ({})", owner, exception.getClass().getSimpleName());
            }
        }
    }

    private void dispatch(long owner, Channel channel) {
        LocalDate date = transaction.execute(status -> claim(owner, channel));
        if (date != null) {
            transaction.executeWithoutResult(status -> {
                AppUser account = users.lockById(owner);
                if (account == null || !account.isEnabled()) return;
                ReminderService.Settings settings = reminders.settings(owner);
                ZonedDateTime now = clock.instant().atZone(ZoneId.of(account.getTimeZone()));
                if (!date.equals(now.toLocalDate()) || !enabled(settings, channel) || !isDue(settings, now)) {
                    record(owner, date, channel, new Outcome("SKIPPED", "提醒时间或设置已变化。"));
                } else if (hasWritten(owner, date)) {
                    record(owner, date, channel, new Outcome("SKIPPED", "当天已有同步的写作活动。"));
                } else {
                    Outcome outcome = channel == Channel.EMAIL ? sendMail(account) : sendPush(owner);
                    record(owner, date, channel, outcome);
                }
            });
        }
    }

    // Commit a unique daily delivery claim before contacting the provider. After an ambiguous
    // interruption we retain the claim and expose UNKNOWN, avoiding a duplicate send.
    private LocalDate claim(long owner, Channel channel) {
        AppUser account = users.lockById(owner);
        if (account == null || !account.isEnabled()) return null;
        ReminderService.Settings settings = reminders.settings(owner);
        ZonedDateTime now = clock.instant().atZone(ZoneId.of(account.getTimeZone()));
        if (!enabled(settings, channel) || !isDue(settings, now)) return null;
        LocalDate date = now.toLocalDate();
        boolean written = hasWritten(owner, date);
        try {
            jdbc.update("""
                    INSERT INTO reminder_sends (owner_id, local_date, channel, status, attempt_count, last_error, started_at)
                    VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP(3))
                    """, owner, date, channel.name(), written ? "SKIPPED" : "SENDING", written ? 0 : 1,
                    written ? "当天已有同步的写作活动。" : null);
        } catch (DuplicateKeyException exception) {
            return null;
        }
        return written ? null : date;
    }

    private boolean hasWritten(long owner, LocalDate date) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM writing_activity WHERE owner_id=? AND activity_date=?)
                """, Boolean.class, owner, date));
    }

    static boolean isDue(ReminderService.Settings settings, ZonedDateTime now) {
        DayOfWeek day = now.getDayOfWeek();
        boolean scheduledDay = switch (settings.type()) {
            case "DAILY" -> true;
            case "WEEKDAYS" -> day.getValue() <= 5;
            case "CUSTOM" -> settings.weekdays().contains(day.getValue());
            default -> false;
        };
        ZonedDateTime due = now.toLocalDate().atTime(LocalTime.parse(settings.time())).atZone(now.getZone());
        return scheduledDay && !now.isBefore(due) && now.isBefore(due.plus(DELIVERY_WINDOW));
    }

    private boolean enabled(ReminderService.Settings settings, Channel channel) {
        return channel == Channel.EMAIL ? settings.emailEnabled() : settings.pushEnabled();
    }

    private Outcome sendMail(AppUser account) {
        try {
            mail.sendWritingReminder(account.getEmail());
            return new Outcome("SENT", null);
        } catch (ApiException exception) {
            return new Outcome("FAILED", exception.getMessage());
        }
    }

    private Outcome sendPush(long owner) {
        List<ReminderService.Subscription> subscriptions = reminders.subscriptions(owner);
        if (subscriptions.isEmpty()) return new Outcome("SKIPPED", "没有有效的浏览器订阅。");
        int successes = 0;
        String failure = null;
        for (var subscription : subscriptions) {
            try {
                int code = push.send(subscription.endpoint(), subscription.p256dh(), subscription.auth());
                if (code >= 200 && code < 300) {
                    successes++;
                } else if (code == 404 || code == 410) {
                    reminders.revoke(owner, subscription.endpoint());
                    failure = "部分或全部浏览器订阅已失效，请重新订阅。";
                } else {
                    failure = "推送服务返回 HTTP " + code + "。";
                }
            } catch (GeneralSecurityException | IOException | ApiException exception) {
                failure = "推送未确认，服务暂不可用或订阅配置无效。";
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                failure = "推送中断，投递结果无法确认。";
                break;
            }
        }
        if (successes == subscriptions.size()) return new Outcome("SENT", null);
        return new Outcome(successes == 0 ? "FAILED" : "PARTIAL", failure);
    }

    private void record(long owner, LocalDate date, Channel channel, Outcome outcome) {
        jdbc.update("""
                UPDATE reminder_sends SET status=?, last_error=?,
                    sent_at=IF(? IN ('SENT', 'PARTIAL'), CURRENT_TIMESTAMP(3), NULL)
                WHERE owner_id=? AND local_date=? AND channel=? AND status='SENDING'
                """, outcome.status(), outcome.message(), outcome.status(), owner, date, channel.name());
    }

    private enum Channel { EMAIL, PUSH }
    private record Outcome(String status, String message) {}
}
