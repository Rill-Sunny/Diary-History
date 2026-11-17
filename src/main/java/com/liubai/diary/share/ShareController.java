package com.liubai.diary.share;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import com.liubai.diary.auth.AppUser;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
public class ShareController {
    private final ShareService service;
    public ShareController(ShareService service) { this.service=service; }

    @GetMapping("/api/shares")
    public List<ShareService.ShareRequest> ownerList(@AuthenticationPrincipal AppUser user) {
        return service.listOwner(user.getId());
    }
    @PostMapping("/api/shares/journals/{journalId}")
    public ShareService.ShareRequest submit(@AuthenticationPrincipal AppUser user,@PathVariable long journalId,
                                            @Valid @RequestBody ValidityRequest request) {
        return service.submit(user.getId(),journalId,request.validityDays());
    }
    @PostMapping("/api/shares/{id}/revoke")
    public ResponseEntity<Void> revoke(@AuthenticationPrincipal AppUser user,@PathVariable long id) {
        service.revoke(user.getId(),id);
        return ResponseEntity.noContent().build();
    }
    @GetMapping("/api/admin/reviews")
    @PreAuthorize("hasRole('ADMIN')")
    public List<ShareService.ShareRequest> queue() { return service.reviewQueue(); }
    @GetMapping("/api/admin/reviews/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ShareService.ReviewSnapshot snapshot(@PathVariable long id) { return service.reviewSnapshot(id); }
    @PostMapping("/api/admin/reviews/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> approve(@AuthenticationPrincipal AppUser user,@PathVariable long id) {
        service.approve(id,user.getId());
        return ResponseEntity.noContent().build();
    }
    @PostMapping("/api/admin/reviews/{id}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> reject(@AuthenticationPrincipal AppUser user,@PathVariable long id,
                                       @Valid @RequestBody RejectRequest request) {
        service.reject(id,user.getId(),request.reason());
        return ResponseEntity.noContent().build();
    }
    @GetMapping("/api/admin/reviews/{id}/media/{position}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<byte[]> reviewMedia(@PathVariable long id,@PathVariable int position) {
        return image(service.reviewMedia(id,position));
    }
    @GetMapping("/api/shares/read/{token}")
    public ShareService.ReviewSnapshot read(@PathVariable String token) { return service.read(token); }
    @GetMapping("/api/shares/read/{token}/media/{requestId}/{position}")
    public ResponseEntity<byte[]> readMedia(@PathVariable String token,@PathVariable long requestId,@PathVariable int position) {
        return image(service.readMedia(token,requestId,position));
    }
    private ResponseEntity<byte[]> image(byte[] bytes) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).contentLength(bytes.length)
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options","nosniff").body(bytes);
    }
    public record ValidityRequest(@Min(1) @Max(90) int validityDays) {}
    public record RejectRequest(@NotBlank @Size(max=500) String reason) {}
}
