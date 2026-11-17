package com.liubai.diary.journal;

import com.liubai.diary.auth.AppUser;
import com.liubai.diary.config.ApiException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/journals")
public class JournalController {
    private final JournalService service;
    public JournalController(JournalService service) { this.service=service; }

    @GetMapping
    public List<JournalService.JournalResponse> list(
            @AuthenticationPrincipal AppUser user,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required=false) String monthDay,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required=false) Long notebookId,
            @RequestParam(required=false) Long tagId,
            @RequestParam(required=false) String q,
            @RequestParam(required=false) String sort,
            @RequestParam(required=false) Integer limit,
            @RequestParam(required=false) Integer offset,
            @RequestParam(defaultValue="false") boolean trash) {
        return service.list(user.getId(),trash,date==null?null:date.toString(),monthDay,
                from==null?null:from.toString(),to==null?null:to.toString(),notebookId,tagId,q,sort,limit,offset);
    }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public JournalService.JournalResponse create(@AuthenticationPrincipal AppUser user,
                                                   @RequestBody JournalService.JournalRequest input,
                                                   @RequestParam(required=false) Long account) {
        requireAccount(user, account);
        return service.save(user.getId(),null,input);
    }
    @PostMapping("/conflict-copy")
    @ResponseStatus(HttpStatus.CREATED)
    public JournalService.JournalResponse conflictCopy(@AuthenticationPrincipal AppUser user,
                                                       @RequestBody JournalService.JournalRequest input,
                                                       @RequestParam(required=false) Long account) {
        requireAccount(user, account);
        return service.saveConflictCopy(user.getId(), input);
    }
    @PostMapping("/save-and-trash")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveAndTrash(@AuthenticationPrincipal AppUser user, @RequestBody JournalService.JournalRequest input,
                             @RequestParam(required=false) Long id, @RequestParam(required=false) Long account) {
        requireAccount(user, account);
        service.saveAndTrash(user.getId(), id, input);
    }
    @GetMapping("/{id}")
    public JournalService.JournalResponse get(@AuthenticationPrincipal AppUser user,@PathVariable long id) {
        return service.get(user.getId(),id);
    }
    @PutMapping("/{id}")
    public JournalService.JournalResponse update(@AuthenticationPrincipal AppUser user,@PathVariable long id,
                                                   @RequestBody JournalService.JournalRequest input,
                                                   @RequestParam(required=false) Long account) {
        requireAccount(user, account);
        return service.save(user.getId(),id,input);
    }
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void trash(@AuthenticationPrincipal AppUser user, @PathVariable long id,
                      @RequestParam(required=false) Long version, @RequestParam(required=false) Long account) {
        requireAccount(user, account);
        service.trash(user.getId(), id, version);
    }
    @PostMapping("/{id}/restore")
    public JournalService.JournalResponse restore(@AuthenticationPrincipal AppUser user,@PathVariable long id) {
        return service.restore(user.getId(),id);
    }
    @DeleteMapping("/{id}/permanent")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePermanently(@AuthenticationPrincipal AppUser user,@PathVariable long id) {
        service.deletePermanently(user.getId(),id);
    }
    private void requireAccount(AppUser user, Long account) {
        if (account != null && account != user.getId()) throw ApiException.notFound();
    }
}
