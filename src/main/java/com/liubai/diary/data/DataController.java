package com.liubai.diary.data;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import com.liubai.diary.auth.AppUser;
import com.liubai.diary.journal.JournalService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/data")
public class DataController {
    private final DataService data;
    public DataController(DataService data) { this.data=data; }
    @GetMapping("/exportable")
    public List<DataMapper.ExportEntry> exportable(@AuthenticationPrincipal AppUser user) {
        return data.exportable(user.getId());
    }
    @GetMapping("/backup")
    public ResponseEntity<DataService.BackupDocument> backup(@AuthenticationPrincipal AppUser user) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore()).body(data.backup(user.getId()));
    }
    @PostMapping("/import")
    public DataService.ImportResult importJournals(@AuthenticationPrincipal AppUser user,
                                                   @Valid @RequestBody ImportRequest request) {
        return data.importJournals(user.getId(),request.entries(),request.operationId());
    }
    @PostMapping("/import/preview")
    public DataService.ImportResult previewImport(@AuthenticationPrincipal AppUser user,
                                                  @Valid @RequestBody ImportRequest request) {
        return data.previewImport(user.getId(),request.entries());
    }
    @PostMapping("/restore")
    public DataService.RestoreResult restore(@AuthenticationPrincipal AppUser user,
                                             @RequestBody RestoreRequest request) {
        return data.restore(user.getId(),request.backup(),request.restoreSettings(),request.mergeNotebooks()!=Boolean.FALSE);
    }
    @PostMapping("/restore/preview")
    public DataService.RestoreResult previewRestore(@AuthenticationPrincipal AppUser user,
                                                    @RequestBody RestoreRequest request) {
        return data.previewRestore(user.getId(),request.backup(),request.mergeNotebooks()!=Boolean.FALSE);
    }
    public record ImportRequest(@NotEmpty @Size(max=2000) List<JournalService.JournalRequest> entries,
                                @NotBlank @Size(max=120) String operationId) {}
    public record RestoreRequest(JsonNode backup,boolean restoreSettings,Boolean mergeNotebooks) {}
}
