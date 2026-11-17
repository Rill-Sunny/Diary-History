package com.liubai.diary.journal;

import com.liubai.diary.auth.UserMapper;
import com.liubai.diary.config.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.List;

@Service
public class AccountCleanupService {
    private static final Logger LOG = LoggerFactory.getLogger(AccountCleanupService.class);
    private final UserMapper users;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate cleanupTransaction;
    private final Path mediaRoot;

    public AccountCleanupService(UserMapper users, JdbcTemplate jdbc, PlatformTransactionManager transactions,
                                 @Value("${app.media.root:./var/media}") String mediaRoot) {
        this.users = users;
        this.jdbc = jdbc;
        this.cleanupTransaction = new TransactionTemplate(transactions);
        this.cleanupTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.mediaRoot = Path.of(mediaRoot).toAbsolutePath().normalize();
    }

    @Transactional
    public void deleteAccount(long userId) {
        if (users.delete(userId) != 1) throw ApiException.notFound();
        jdbc.update("INSERT INTO account_media_cleanup (owner_id) VALUES (?)", userId);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                process(userId);
            }
        });
    }

    @Scheduled(fixedDelayString = "PT5M")
    public void retryPendingMediaCleanup() {
        List<Long> owners = jdbc.queryForList("""
                SELECT owner_id FROM account_media_cleanup WHERE ready_at<=CURRENT_TIMESTAMP(3)
                ORDER BY ready_at LIMIT 50
                """, Long.class);
        owners.forEach(this::process);
    }

    private void process(long owner) {
        try {
            cleanupTransaction.executeWithoutResult(status -> {
                // Serialize retries of this specific directory across the after-commit hook and schedulers.
                List<Long> claimed = jdbc.queryForList("""
                        SELECT owner_id FROM account_media_cleanup
                        WHERE owner_id=? AND ready_at<=CURRENT_TIMESTAMP(3) FOR UPDATE
                        """, Long.class, owner);
                if (claimed.isEmpty()) return;
                try {
                    if (users.findById(owner) == null) clearMediaDirectory(owner);
                    jdbc.update("DELETE FROM account_media_cleanup WHERE owner_id=?", owner);
                } catch (IOException | UncheckedIOException | SecurityException exception) {
                    LOG.warn("Account media cleanup for {} will retry ({})", owner, exception.getClass().getSimpleName());
                    jdbc.update("""
                            UPDATE account_media_cleanup SET ready_at=DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 5 MINUTE)
                            WHERE owner_id=?
                            """, owner);
                }
            });
        } catch (RuntimeException exception) {
            LOG.warn("Could not process account media cleanup {} ({})", owner, exception.getClass().getSimpleName());
        }
    }

    private void clearMediaDirectory(long owner) throws IOException {
        Path ownerDirectory = mediaRoot.resolve(Long.toString(owner)).normalize();
        if (owner < 1 || !mediaRoot.equals(ownerDirectory.getParent())) {
            throw new SecurityException("Invalid account media directory");
        }
        if (Files.notExists(ownerDirectory, LinkOption.NOFOLLOW_LINKS)) return;
        List<Path> deletionOrder;
        try (var files = Files.walk(ownerDirectory)) {
            deletionOrder = files.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : deletionOrder) Files.deleteIfExists(path);
    }
}
