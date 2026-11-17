package com.liubai.diary.journal;

import com.liubai.diary.config.ApiException;
import com.liubai.diary.media.MediaService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.Objects;

@Service
public class TagIconService {
    private static final long MAX_SIZE = 1024 * 1024;

    private final TagMapper tags;
    private final TagIconMapper reviews;
    private final MediaService media;
    private final JournalMapper journals;

    public TagIconService(TagMapper tags, TagIconMapper reviews, MediaService media, JournalMapper journals) {
        this.tags = tags;
        this.reviews = reviews;
        this.media = media;
        this.journals = journals;
    }

    @Transactional
    public void submit(long ownerId, long tagId, MultipartFile file) {
        requireOwnedTag(ownerId, tagId);
        if (file.getSize() > MAX_SIZE) throw ApiException.badRequest("标签图标不能超过 1 MB。");
        submitRestored(ownerId, tagId, media.upload(ownerId, file).id());
    }

    @Transactional
    public void submitRestored(long ownerId, long tagId, long mediaId) {
        requireOwnedTag(ownerId, tagId);
        if (media.get(ownerId, mediaId).size() > MAX_SIZE) {
            throw ApiException.badRequest("标签图标不能超过 1 MB。");
        }
        reviews.supersedePending(tagId);
        reviews.submit(tagId, mediaId);
    }

    @Transactional
    public void decide(long reviewerId, long id, boolean approve, String reason) {
        TagIconMapper.Review review = reviews.find(id);
        if (review == null) throw ApiException.notFound();
        requireOwnedTag(review.ownerId(), review.tagId());
        String note = reason == null ? "" : reason.strip();
        if (!approve && (note.isBlank() || note.length() > 500)) {
            throw ApiException.badRequest("请填写 1 到 500 字的拒绝原因。");
        }
        if (reviews.decide(id, reviewerId, approve ? "APPROVED" : "REJECTED", approve ? null : note) != 1) {
            throw ApiException.conflict("这份图标申请已处理或被替换。", null);
        }
        if (approve) tags.approveIcon(review.tagId(), review.mediaId());
    }

    private void requireOwnedTag(long ownerId, long tagId) {
        if (journals.lockOwner(ownerId) == null || !Objects.equals(tags.lockOwner(tagId), ownerId)) throw ApiException.notFound();
    }
}
