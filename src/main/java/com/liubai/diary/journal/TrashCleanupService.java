package com.liubai.diary.journal;

import com.liubai.diary.media.MediaMapper;
import com.liubai.diary.media.MediaService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;

@Service
public class TrashCleanupService {
    private final JournalMapper journals;
    private final MediaMapper mediaRows;
    private final MediaService media;
    private final TransactionTemplate transactions;
    public TrashCleanupService(JournalMapper journals, MediaMapper mediaRows, MediaService media,
                               TransactionTemplate transactions) {
        this.journals=journals;this.mediaRows=mediaRows;this.media=media;this.transactions=transactions;
    }
    @Scheduled(cron="${app.trash.cleanup-cron:0 15 3 * * *}",zone="UTC")
    public void purgeExpiredTrash() {
        journals.purgeExpiredTrash();
        List<Long> owners = journals.orphanedMedia(null).stream().map(MediaMapper.MediaRow::getOwnerId).distinct().toList();
        for (long ownerId : owners) {
            List<MediaMapper.MediaRow> removed = transactions.execute(status -> {
                if (journals.lockOwner(ownerId) == null) return List.of();
                // Recheck references under the same owner lock used by journal and library writes.
                return journals.orphanedMedia(ownerId).stream()
                        .filter(row -> mediaRows.deleteOwned(row.getId(), ownerId) == 1).toList();
            });
            if (removed != null) removed.forEach(media::deleteStored);
        }
    }
}
