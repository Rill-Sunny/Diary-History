package com.liubai.diary.reminder;

import com.liubai.diary.auth.AppUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reminders")
public class ReminderController {
    private final ReminderService reminders;

    public ReminderController(ReminderService reminders) {
        this.reminders = reminders;
    }

    @GetMapping
    public ReminderService.Status status(@AuthenticationPrincipal AppUser user) {
        return reminders.status(user.getId());
    }

    @PutMapping
    public ReminderService.Status save(@AuthenticationPrincipal AppUser user, @RequestBody ReminderService.Settings settings) {
        return reminders.update(user.getId(), settings);
    }

    @PostMapping("/subscription")
    public ReminderService.Status subscribe(@AuthenticationPrincipal AppUser user, @Valid @RequestBody Subscription input) {
        return reminders.subscribe(user.getId(), new ReminderService.Subscription(input.endpoint(), input.p256dh(), input.auth()));
    }

    @DeleteMapping("/subscription")
    public ReminderService.Status unsubscribe(@AuthenticationPrincipal AppUser user, @Valid @RequestBody Endpoint input) {
        return reminders.unsubscribe(user.getId(), input.endpoint());
    }

    public record Subscription(@NotBlank @Size(max=2048) String endpoint, @NotBlank @Size(max=256) String p256dh,
                               @NotBlank @Size(max=128) String auth) {}
    public record Endpoint(@NotBlank @Size(max=2048) String endpoint) {}
}
