package com.liubai.diary.media;

import com.liubai.diary.auth.AppUser;
import com.liubai.diary.config.ApiException;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/media")
public class MediaController {
    private final MediaService media;
    public MediaController(MediaService media) { this.media = media; }
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MediaService.Uploaded upload(@AuthenticationPrincipal AppUser user, @RequestPart("file") MultipartFile file,
                                       @RequestParam(required = false) Long account) {
        if (account != null && account != user.getId()) throw ApiException.notFound();
        return media.upload(user.getId(), file);
    }
    @GetMapping("/{id}")
    public ResponseEntity<FileSystemResource> image(@AuthenticationPrincipal AppUser user, @PathVariable long id) {
        MediaService.StoredFile file = media.get(user.getId(), id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.mediaType()))
                .contentLength(file.size()).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff").body(new FileSystemResource(file.path()));
    }
}
