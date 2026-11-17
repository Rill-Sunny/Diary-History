package com.liubai.diary.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
public class AuthMailLimiter {
    private static final int WINDOW_SECONDS = 600;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AuthMailLimiter(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean acquire(String addressHash) {
        long window = clock.instant().getEpochSecond() / WINDOW_SECONDS;
        jdbc.update("""
                INSERT INTO auth_mail_limits (ip_hash, window_number)
                VALUES (?, ?) ON DUPLICATE KEY UPDATE ip_hash=ip_hash
                """, addressHash, window);
        return jdbc.update("""
                UPDATE auth_mail_limits SET request_count=request_count+1
                WHERE ip_hash=? AND window_number=? AND request_count<8
                """, addressHash, window) == 1;
    }

    @Scheduled(fixedDelayString = "PT1H")
    public void removeOldWindows() {
        jdbc.update("DELETE FROM auth_mail_limits WHERE window_number<?",
                clock.instant().getEpochSecond() / WINDOW_SECONDS - 1);
    }
}
