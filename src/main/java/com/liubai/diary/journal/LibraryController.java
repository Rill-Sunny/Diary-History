package com.liubai.diary.journal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import com.liubai.diary.auth.AppUser;
import com.liubai.diary.config.ApiException;
import com.liubai.diary.media.MediaService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/library")
public class LibraryController {
    private final NotebookMapper notebooks;
    private final TagMapper tags;
    private final JournalMapper journals;
    private final MediaService media;
    private final TagIconService tagIcons;

    public LibraryController(NotebookMapper notebooks, TagMapper tags, JournalMapper journals,
                             MediaService media, TagIconService tagIcons) {
        this.notebooks=notebooks; this.tags=tags; this.journals=journals; this.media=media; this.tagIcons=tagIcons;
    }
    @GetMapping("/notebooks")
    public List<NotebookMapper.NotebookRow> notebooks(@AuthenticationPrincipal AppUser user) {
        return notebooks.list(user.getId());
    }
    @PostMapping("/notebooks")
    @ResponseStatus(HttpStatus.CREATED)
    public NotebookMapper.NotebookRow createNotebook(@AuthenticationPrincipal AppUser user,@Valid @RequestBody NotebookRequest input) {
        String name=input.name().strip();
        if (name.isBlank()) throw ApiException.badRequest("日记本名称不能为空。");
        notebooks.create(user.getId(),name);
        return notebooks.list(user.getId()).stream().filter(row->row.name().equals(name)).findFirst().orElseThrow(ApiException::notFound);
    }
    @DeleteMapping("/notebooks/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void deleteNotebook(@AuthenticationPrincipal AppUser user,@PathVariable long id) {
        if (journals.lockOwner(user.getId()) == null) throw ApiException.notFound();
        Long owned=notebooks.ownedId(id,user.getId());
        if (owned==null) throw ApiException.notFound();
        if (notebooks.list(user.getId()).stream().anyMatch(row->row.id()==id&&row.isDefault()))
            throw ApiException.badRequest("默认日记本不能删除。");
        journals.trashNotebookEntries(user.getId(),id);
        journals.revokeNotebookLinks(user.getId(),id);
        journals.revokeNotebookRequests(user.getId(),id);
        if (notebooks.delete(id,user.getId())!=1) throw ApiException.notFound();
    }

    @PostMapping("/notebooks/{id}/cover")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void setNotebookCover(@AuthenticationPrincipal AppUser user, @PathVariable long id,
                                 @RequestParam("file") MultipartFile file) {
        if (journals.lockOwner(user.getId()) == null || notebooks.ownedId(id, user.getId()) == null) {
            throw ApiException.notFound();
        }
        notebooks.setCover(id, user.getId(), media.upload(user.getId(), file).id());
    }

    @DeleteMapping("/notebooks/{id}/cover")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearNotebookCover(@AuthenticationPrincipal AppUser user, @PathVariable long id) {
        if (notebooks.setCover(id, user.getId(), null) == 0) throw ApiException.notFound();
    }
    @GetMapping("/tags")
    public List<TagMapper.TagRow> tags(@AuthenticationPrincipal AppUser user) { return tags.list(user.getId()); }
    @PostMapping("/tags")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public TagMapper.TagRow createTag(@AuthenticationPrincipal AppUser user,@Valid @RequestBody TagRequest input) {
        String name=input.name().strip();
        if (name.isBlank()) throw ApiException.badRequest("标签名称不能为空。");
        String icon=input.icon()==null?"leaf":input.icon();
        if (!Set.of("leaf","book","heart","star","tag","clock","sun","moon","coffee").contains(icon))
            throw ApiException.badRequest("请选择内置标签图标。");
        String style=input.style()==null?"soft":input.style();
        if (!Set.of("soft", "outline", "solid").contains(style)) throw ApiException.badRequest("请选择标签样式。");
        tags.insert(user.getId(),name,input.color()==null?"#798744":input.color(),icon);
        TagMapper.TagRow tag=tags.findByName(user.getId(),name);
        tags.setStyle(tag.id(),user.getId(),style);
        return tags.findByName(user.getId(),name);
    }

    @PostMapping("/tags/{id}/icon")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void submitTagIcon(@AuthenticationPrincipal AppUser user, @PathVariable long id,
                              @RequestParam("file") MultipartFile file) {
        tagIcons.submit(user.getId(), id, file);
    }
    @DeleteMapping("/tags/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void deleteTag(@AuthenticationPrincipal AppUser user,@PathVariable long id) {
        if (journals.lockOwner(user.getId()) == null) throw ApiException.notFound();
        if (tags.delete(user.getId(),id)!=1) throw ApiException.notFound();
    }
    public record NotebookRequest(@NotBlank @Size(max=60) String name) {}
    public record TagRequest(@NotBlank @Size(max=40) String name,
                             @Pattern(regexp="^#[0-9A-Fa-f]{6}$") String color,
                             @Size(max=40) String icon, @Size(max=12) String style) {}
}
