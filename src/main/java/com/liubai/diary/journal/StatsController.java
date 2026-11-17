package com.liubai.diary.journal;

import com.liubai.diary.auth.AppUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.format.annotation.DateTimeFormat;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/stats")
public class StatsController {
    private final JournalService journals;
    public StatsController(JournalService journals) { this.journals=journals; }
    @GetMapping("/activity")
    public List<JournalMapper.ActivityRow> activity(@AuthenticationPrincipal AppUser user) {
        return journals.activity(user.getId());
    }
    @GetMapping("/coverage")
    public List<JournalMapper.ActivityRow> coverage(@AuthenticationPrincipal AppUser user,
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to) {
        return journals.coverage(user.getId(), from, to);
    }
}
