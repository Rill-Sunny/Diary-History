package com.liubai.diary.journal;

import com.liubai.diary.auth.AppUser;
import com.liubai.diary.config.ApiException;
import com.liubai.diary.media.MediaService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/admin/tag-icons")
public class TagIconReviewController {
    private final TagIconMapper reviews;
    private final TagIconService icons;
    private final MediaService media;

    public TagIconReviewController(TagIconMapper reviews, TagIconService icons, MediaService media) {
        this.reviews = reviews;
        this.icons = icons;
        this.media = media;
    }

    @GetMapping
    public List<ReviewView> list() {
        return reviews.list().stream().map(ReviewView::new).toList();
    }

    @GetMapping("/{id}/image")
    public ResponseEntity<Resource> image(@PathVariable long id) {
        TagIconMapper.Review review = reviews.find(id);
        if (review == null) throw ApiException.notFound();
        MediaService.StoredFile file = media.get(review.ownerId(), review.mediaId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(file.mediaType())).contentLength(file.size())
                .body(new FileSystemResource(file.path()));
    }

    @PostMapping("/{id}/approve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void approve(@AuthenticationPrincipal AppUser reviewer, @PathVariable long id) {
        icons.decide(reviewer.getId(), id, true, null);
    }

    @PostMapping("/{id}/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reject(@AuthenticationPrincipal AppUser reviewer, @PathVariable long id, @RequestBody Rejection input) {
        icons.decide(reviewer.getId(), id, false, input.reason());
    }

    public record Rejection(String reason) {}

    public record ReviewView(long id, long tagId, String tagName, String submitter, LocalDateTime submittedAt,
                             String status, String reason, String imageUrl) {
        ReviewView(TagIconMapper.Review review) {
            this(review.id(), review.tagId(), review.tagName(), review.submitter(), review.submittedAt(), review.status(),
                    review.reason(), "/api/admin/tag-icons/" + review.id() + "/image");
        }
    }
}
