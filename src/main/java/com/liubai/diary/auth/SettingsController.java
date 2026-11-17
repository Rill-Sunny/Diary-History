package com.liubai.diary.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import com.liubai.diary.config.ApiException;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.time.ZoneId;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {
    private final UserMapper users;
    public SettingsController(UserMapper users) { this.users=users; }
    @PutMapping
    public AuthController.UserResponse update(@AuthenticationPrincipal AppUser principal,
                                               @Valid @RequestBody SettingsRequest input) {
        try { ZoneId.of(input.timeZone()); }
        catch (RuntimeException e) { throw ApiException.badRequest("请选择有效时区。"); }
        if (users.updateSettings(principal.getId(),input.nickname().strip(),input.timeZone(),
                input.theme()==null?"light":input.theme(),input.accent()==null?"olive":input.accent())!=1)
            throw ApiException.notFound();
        return AuthController.response(users.findById(principal.getId()));
    }

    @GetMapping("/avatar")
    public ResponseEntity<byte[]> avatar(@AuthenticationPrincipal AppUser user) {
        UserMapper.AvatarRow avatar = users.readAvatar(user.getId());
        if (avatar == null) throw ApiException.notFound();
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.noStore()).body(avatar.png());
    }

    @PostMapping(value = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AuthController.UserResponse updateAvatar(@AuthenticationPrincipal AppUser user, @RequestPart("file") MultipartFile file) {
        if (users.updateAvatar(user.getId(), ProfileAvatar.thumbnail(file)) != 1) throw ApiException.notFound();
        return AuthController.response(users.findById(user.getId()));
    }

    @DeleteMapping("/avatar")
    public AuthController.UserResponse deleteAvatar(@AuthenticationPrincipal AppUser user) {
        if (users.updateAvatar(user.getId(), null) != 1) throw ApiException.notFound();
        return AuthController.response(users.findById(user.getId()));
    }
    public record SettingsRequest(@NotBlank @Size(max=40) String nickname,@NotBlank @Size(max=64) String timeZone,
                                  @Pattern(regexp="^(light|dark)$") String theme,
                                  @Pattern(regexp="^(olive|blue|rose)$") String accent) {}
}
