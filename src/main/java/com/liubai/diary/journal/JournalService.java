package com.liubai.diary.journal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.liubai.diary.auth.AppUser;
import com.liubai.diary.auth.UserMapper;
import com.liubai.diary.config.ApiException;
import com.liubai.diary.media.MediaService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

@Service
public class JournalService {
    private static final Set<String> NODE_TYPES = Set.of("doc", "paragraph", "text", "heading", "blockquote",
            "bulletList", "orderedList", "listItem", "horizontalRule", "hardBreak", "codeBlock", "image");
    private static final Set<String> MARK_TYPES = Set.of("bold", "italic", "strike", "code", "underline", "link", "textStyle");
    private final JournalMapper journals;
    private final NotebookMapper notebooks;
    private final UserMapper users;
    private final MediaService media;
    private final ObjectMapper json;
    private final Clock clock;

    public JournalService(JournalMapper journals, NotebookMapper notebooks, UserMapper users,
                          MediaService media, ObjectMapper json, Clock clock) {
        this.journals=journals; this.notebooks=notebooks; this.users=users; this.media=media; this.json=json;
        this.clock=clock;
    }

    public List<JournalResponse> list(long ownerId, boolean trash, String dateValue, String monthDay,
                                      String fromValue, String toValue, Long notebookId, Long tagId,
                                      String query, String sort, Integer limit) {
        return list(ownerId, trash, dateValue, monthDay, fromValue, toValue, notebookId, tagId, query, sort, limit, 0);
    }

    public List<JournalResponse> list(long ownerId, boolean trash, String dateValue, String monthDay,
                                      String fromValue, String toValue, Long notebookId, Long tagId,
                                      String query, String sort, Integer limit, Integer offset) {
        LocalDate date=parseDate(dateValue);
        LocalDate from=parseDate(fromValue);
        LocalDate to=parseDate(toValue);
        if (monthDay != null && !monthDay.matches("(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])"))
            throw ApiException.badRequest("月份日期格式应为 MM-DD。");
        if (from != null && to != null && from.isAfter(to)) throw ApiException.badRequest("日期范围无效。");
        int safeLimit = limit == null ? 200 : Math.max(1, Math.min(limit, 500));
        String q = query == null ? null : query.trim();
        if (q != null && q.length() > 120) q = q.substring(0, 120);
        if (q != null) q = q.replace("=", "==").replace("%", "=%").replace("_", "=_");
        return journals.list(ownerId, trash, date, monthDay, from, to, notebookId, tagId, q,
                "date-asc".equals(sort) ? "date-asc" : "date-desc", safeLimit, offset == null ? 0 : Math.max(0, offset))
                .stream().map(row -> response(ownerId, row)).toList();
    }

    public JournalResponse get(long ownerId, long id) {
        JournalMapper.JournalRow row=journals.findOwned(id, ownerId);
        if (row == null) throw ApiException.notFound();
        return response(ownerId, row);
    }

    @Transactional
    public JournalResponse save(long ownerId, Long id, JournalRequest input) {
        return saveInternal(ownerId,id,input,true);
    }
    @Transactional
    public JournalResponse importOne(long ownerId, JournalRequest input) {
        return saveInternal(ownerId,null,input,false);
    }

    @Transactional
    public JournalResponse saveConflictCopy(long ownerId, JournalRequest input) {
        return saveInternal(ownerId, null, input, false);
    }

    @Transactional
    public void saveAndTrash(long ownerId, Long id, JournalRequest input) {
        requireActiveOwner(ownerId);
        String operationId = validClientId(input.operationId());
        if (operationId != null) {
            JournalMapper.SaveOperation receipt = journals.findSaveOperation(ownerId, operationId);
            if (receipt != null) {
                if (receipt.journalId() == null) return;
                JournalMapper.JournalRow row = journals.findAnyOwned(receipt.journalId(), ownerId);
                if (row != null && row.deletedAt() != null && row.version() == receipt.journalVersion() + 1 &&
                        (id == null || id.equals(receipt.journalId()))) return;
            }
        }
        JournalResponse saved = saveInternal(ownerId, id, input, true);
        trash(ownerId, saved.id(), saved.version());
    }

    public JsonNode normalizeImportContent(long ownerId, JsonNode raw) {
        return sanitizeDocument(ownerId, raw);
    }

    private JournalResponse saveInternal(long ownerId, Long id, JournalRequest input,boolean trackActivity) {
        if (input == null || input.content() == null) throw ApiException.badRequest("日记正文不能为空。");
        requireActiveOwner(ownerId);
        AppUser owner=users.findById(ownerId);
        if (owner == null || !owner.getEnabled()) throw ApiException.notFound();
        LocalDate journalDate=input.journalDate();
        if (journalDate == null) throw ApiException.badRequest("请选择日记日期。");
        LocalDate today=LocalDate.now(clock.withZone(safeZone(owner.getTimeZone())));
        if (journalDate.isAfter(today) || journalDate.getYear() < 1000) throw ApiException.badRequest("日记日期必须在 1000 年至今天之间。");

        ObjectNode clean=sanitizeDocument(ownerId, input.content());
        String plainText=extractText(clean).strip();
        String contentJson=clean.toString();
        if (contentJson.length() > 2_000_000 || plainText.length() > 120_000)
            throw ApiException.badRequest("日记内容过长，请分成多篇保存。");
        if (!hasVisibleText(plainText) && !containsImage(clean))
            throw ApiException.badRequest("空白正文不会创建日记。");

        Long notebookId=input.notebookId();
        if (notebookId == null || notebookId <= 0) notebookId=notebooks.defaultId(ownerId);
        if (notebookId == null || notebooks.ownedId(notebookId, ownerId) == null)
            throw ApiException.badRequest("请选择当前账号拥有的日记本。");
        List<Long> tagIds=normalizeTags(input.tagIds());
        if (!tagIds.isEmpty() && journals.countOwnedTags(ownerId, tagIds) != tagIds.size())
            throw ApiException.badRequest("所选标签中包含当前账号不可用的标签。");

        String title=(input.title() == null ? "" : input.title().strip());
        boolean automatic=input.automaticTitle() || title.isBlank();
        if (automatic) title=journalDate.format(DateTimeFormatter.ofPattern("yyyy年M月d日"));
        if (title.length() > 255) throw ApiException.badRequest("标题不能超过 255 个字符。");
        String clientId=validClientId(input.clientId());
        String operationId=validClientId(input.operationId());
        List<LocalDate> activityDates = activityDates(input.activityDates(), today);
        String requestHash = operationId == null ? null : requestHash(id, input.expectedVersion(), journalDate,
                title, automatic, clean, notebookId, tagIds, clientId, input.activityDates());
        if (operationId != null) {
            JournalMapper.SaveOperation receipt = journals.findSaveOperation(ownerId, operationId);
            if (receipt != null) {
                if (!receipt.requestHash().equals(requestHash)) throw ApiException.conflict("操作标识已经用于不同的编辑内容。");
                if (receipt.journalId() == null) throw ApiException.notFound();
                JournalResponse current = get(ownerId, receipt.journalId());
                if (current.version() == receipt.journalVersion()) return current;
                throw editConflict(current);
            }
        }

        JournalMapper.JournalRow previous=null;
        if (id == null && clientId != null) {
            previous=journals.findByClient(ownerId, clientId);
            if (previous != null) {
                if (previous.deletedAt() != null) throw ApiException.notFound();
                JournalResponse current=response(ownerId,previous);
                List<Long> currentTags=current.tags().stream().map(JournalMapper.TagRow::id).sorted().toList();
                List<Long> requestedTags=tagIds.stream().sorted().toList();
                boolean identical=current.content().equals(clean)&&previous.title().equals(title)&&
                        previous.automaticTitle()==automatic&&previous.journalDate().equals(journalDate)&&
                        Objects.equals(previous.notebookId(),notebookId)&&currentTags.equals(requestedTags);
                if(identical) return recordReceipt(ownerId, operationId, requestHash, current);
                throw ApiException.conflict("这条新日记可能已保存；为避免覆盖服务器内容，请先检查当前版本。",
                        Map.of("current",current));
            }
        } else if (id != null) {
            previous=journals.findOwned(id, ownerId);
            if (previous == null) throw ApiException.notFound();
        }

        JournalResponse current = previous == null ? null : response(ownerId, previous);
        if (id != null && input.expectedVersion() == null) {
            throw ApiException.badRequest("缺少日记版本号，请重新载入后再保存。");
        }
        boolean changedText = current == null || !current.content().equals(clean) ||
                current.automaticTitle() != automatic || (!automatic && !current.title().equals(title));
        if (current != null && input.expectedVersion() != null && current.version() != input.expectedVersion()) {
            throw editConflict(current);
        }
        if (current != null && !changedText && current.journalDate().equals(journalDate) &&
                Objects.equals(current.notebookId(), notebookId) && new HashSet<>(tagIds).equals(
                new HashSet<>(current.tags().stream().map(JournalMapper.TagRow::id).toList()))) {
            return recordReceipt(ownerId, operationId, requestHash, current);
        }
        if (id == null) {
            JournalMapper.NewJournal row=new JournalMapper.NewJournal();
            row.setOwnerId(ownerId); row.setNotebookId(notebookId); row.setClientId(clientId);
            row.setJournalDate(journalDate); row.setTitle(title); row.setAutomaticTitle(automatic);
            row.setContentJson(contentJson); row.setPlainText(plainText);
            journals.insert(row);
            id=row.getId();
            replaceTags(ownerId,id,tagIds);
            if (changedText && trackActivity) recordActivity(ownerId, id, "CREATE", activityDates);
        } else {
            Long expected=input.expectedVersion();
            if (expected == null) throw ApiException.badRequest("缺少日记版本号，请重新载入后再保存。");
            JournalMapper.UpdateJournal row=new JournalMapper.UpdateJournal();
            row.setId(id); row.setOwnerId(ownerId); row.setNotebookId(notebookId); row.setClientId(clientId);
            row.setJournalDate(journalDate); row.setTitle(title); row.setAutomaticTitle(automatic);
            row.setContentJson(contentJson); row.setPlainText(plainText); row.setExpectedVersion(expected);
            if (journals.update(row) != 1) {
                throw editConflict(get(ownerId, id));
            }
            replaceTags(ownerId,id,tagIds);
            if (changedText && trackActivity) recordActivity(ownerId, id, "EDIT", activityDates);
        }
        return recordReceipt(ownerId, operationId, requestHash, get(ownerId,id));
    }

    @Transactional
    public void trash(long ownerId,long id) {
        trash(ownerId, id, null);
    }

    @Transactional
    public void trash(long ownerId, long id, Long version) {
        requireActiveOwner(ownerId);
        if (journals.trash(id, ownerId, version) != 1) {
            JournalMapper.JournalRow existing = journals.findAnyOwned(id, ownerId);
            if (version != null && existing != null) {
                if (existing.deletedAt() != null && existing.version() == version + 1) return;
                if (existing.deletedAt() == null) throw editConflict(response(ownerId, existing));
            }
            throw ApiException.notFound();
        }
        journals.revokeJournalLinks(id);
        journals.revokeJournalRequests(id);
    }
    @Transactional
    public JournalResponse restore(long ownerId,long id) {
        requireActiveOwner(ownerId);
        if (journals.restore(id,ownerId)!=1) throw ApiException.notFound();
        return get(ownerId,id);
    }
    @Transactional
    public void deletePermanently(long ownerId,long id) {
        requireActiveOwner(ownerId);
        if (journals.deletePermanently(id,ownerId)!=1) throw ApiException.notFound();
    }
    public List<JournalMapper.ActivityRow> activity(long ownerId) { return journals.activity(ownerId); }

    public List<JournalMapper.ActivityRow> coverage(long ownerId, LocalDate from, LocalDate to) {
        if (from.isAfter(to) || to.isAfter(from.plusYears(2))) throw ApiException.badRequest("日历日期范围无效。");
        return journals.coverage(ownerId, from, to);
    }

    @Transactional
    public void importActivity(long ownerId,String operationId) {
        AppUser owner=users.findById(ownerId);
        if(owner==null) throw ApiException.notFound();
        LocalDate date=LocalDate.now(clock.withZone(safeZone(owner.getTimeZone())));
        String stable=operationId==null?"batch":operationId.substring(0,Math.min(operationId.length(),130));
        journals.addActivity(ownerId,null,"import:"+stable,date,"IMPORT");
    }
    @Transactional
    public void importActivityAt(long ownerId,String stableKey,LocalDate date) {
        if(users.findById(ownerId)==null) throw ApiException.notFound();
        String stable=stableKey==null?"backup":stableKey.substring(0,Math.min(stableKey.length(),130));
        journals.addActivity(ownerId,null,"backup:"+stable+":"+date,date,"IMPORT");
    }

    private void recordActivity(long ownerId, long id, String kind, List<LocalDate> dates) {
        for (LocalDate date : dates) journals.addActivity(ownerId,id,"journal:"+id+":"+date,date,kind);
    }

    private List<LocalDate> activityDates(List<LocalDate> dates, LocalDate today) {
        if (dates == null || dates.isEmpty()) return List.of(today);
        if (dates.size() > 3660 || dates.stream().anyMatch(date -> date == null || date.getYear() < 1000 || date.isAfter(today.plusDays(1)))) {
            throw ApiException.badRequest("实际写作日期无效。");
        }
        return dates.stream().distinct().sorted().toList();
    }

    private void requireActiveOwner(long ownerId) {
        if (journals.lockOwner(ownerId) == null) throw ApiException.notFound();
    }

    private ApiException editConflict(JournalResponse current) {
        return ApiException.conflict("这篇日记已在其他位置更新，请选择加载服务器版本或另存为副本。",
                Map.of("current", current));
    }

    private JournalResponse recordReceipt(long ownerId, String operationId, String hash, JournalResponse saved) {
        if (operationId != null) journals.addSaveOperation(ownerId, operationId, saved.id(), saved.version(), hash);
        return saved;
    }

    private String requestHash(Object... fields) {
        try {
            byte[] body = json.valueToTree(fields).toString().getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
    private void replaceTags(long ownerId,long journalId,List<Long> tagIds) {
        journals.clearTags(ownerId,journalId);
        for (Long tagId:tagIds) journals.addTag(ownerId,journalId,tagId);
    }
    private List<Long> normalizeTags(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        if (ids.size()>20 || ids.stream().anyMatch(Objects::isNull)) throw ApiException.badRequest("每篇日记最多选择 20 个标签。");
        return ids.stream().distinct().toList();
    }
    private String validClientId(String value) {
        if (value == null || value.isBlank()) return null;
        if (value.length()>120 || !value.matches("[A-Za-z0-9_-]+")) throw ApiException.badRequest("日记操作标识无效。");
        return value;
    }
    private JournalResponse response(long ownerId,JournalMapper.JournalRow row) {
        try {
            JsonNode content=json.readTree(row.contentJson());
            List<JournalMapper.TagRow> tags=journals.tagsFor(ownerId,row.id());
            return new JournalResponse(row.id(),row.journalDate(),row.title(),row.automaticTitle(),content,
                    row.plainText(),row.version(),row.notebookId(),row.notebookName(),tags,
                    row.createdAt().toInstant(ZoneOffset.UTC),row.updatedAt().toInstant(ZoneOffset.UTC),
                    row.deletedAt() == null ? null : row.deletedAt().toInstant(ZoneOffset.UTC));
        } catch (Exception e) { throw new IllegalStateException("Stored diary JSON is invalid",e); }
    }
    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        try { return LocalDate.parse(value); }
        catch (RuntimeException e) { throw ApiException.badRequest("日期格式无效。"); }
    }
    private ZoneId safeZone(String value) {
        try { return ZoneId.of(value == null ? "Asia/Shanghai" : value); }
        catch (RuntimeException e) { return ZoneId.of("Asia/Shanghai"); }
    }

    private ObjectNode sanitizeDocument(long ownerId,JsonNode raw) {
        if (!raw.isObject() || !"doc".equals(raw.path("type").asText()))
            throw ApiException.badRequest("正文结构无法识别。");
        Counter counter=new Counter();
        JsonNode clean=sanitizeNode(ownerId,raw,0,counter);
        if (!clean.isObject()) throw ApiException.badRequest("正文结构无法识别。");
        return (ObjectNode)clean;
    }
    private JsonNode sanitizeNode(long ownerId,JsonNode raw,int depth,Counter count) {
        if (depth>40 || ++count.nodes>10_000 || !raw.isObject()) throw ApiException.badRequest("正文结构过于复杂。");
        String type=raw.path("type").asText("");
        if (!NODE_TYPES.contains(type)) throw ApiException.badRequest("正文包含不支持的内容类型。");
        ObjectNode out=json.createObjectNode().put("type",type);
        JsonNode attrs=raw.get("attrs");
        if (attrs!=null && attrs.isObject()) {
            ObjectNode safe=json.createObjectNode();
            if (type.equals("heading")) {
                int level=attrs.path("level").asInt(2);
                safe.put("level",Math.max(1,Math.min(6,level)));
            } else if (type.equals("orderedList")) {
                safe.put("start",Math.max(1,attrs.path("start").asInt(1)));
            } else if (type.equals("image")) {
                long mediaId=attrs.path("mediaId").asLong(0);
                if (mediaId<=0 || !media.owns(ownerId,mediaId)) throw ApiException.badRequest("图片不属于当前账号或已无法访问。");
                safe.put("mediaId",mediaId);
                safe.put("src","/api/media/"+mediaId);
                safe.put("alt",bounded(attrs.path("alt").asText(""),500));
                safe.put("title",bounded(attrs.path("title").asText(""),500));
                safe.put("width",Math.max(30,Math.min(100,attrs.path("width").asInt(100))));
                int rotation=attrs.path("rotation").asInt(0);
                safe.put("rotation",((rotation%360)+360)%360);
                safe.put("cropped",attrs.path("cropped").asBoolean(false));
            }
            if (safe.size()>0) out.set("attrs",safe);
        } else if (type.equals("image")) throw ApiException.badRequest("图片引用无效。");
        if (raw.has("text")) {
            if (!type.equals("text") || !raw.get("text").isTextual()) throw ApiException.badRequest("正文文本结构无效。");
            String text=raw.path("text").asText();
            if (text.length()>120_000) throw ApiException.badRequest("日记内容过长。");
            out.put("text",text);
        }
        JsonNode marks=raw.get("marks");
        if (marks!=null) {
            if (!marks.isArray() || marks.size()>8) throw ApiException.badRequest("文字格式无效。");
            ArrayNode safeMarks=json.createArrayNode();
            for (JsonNode mark:marks) safeMarks.add(sanitizeMark(mark));
            if (safeMarks.size()>0) out.set("marks",safeMarks);
        }
        JsonNode children=raw.get("content");
        if (children!=null) {
            if (!children.isArray() || children.size()>10_000) throw ApiException.badRequest("正文段落过多。");
            ArrayNode safeChildren=json.createArrayNode();
            for (JsonNode child:children) safeChildren.add(sanitizeNode(ownerId,child,depth+1,count));
            out.set("content",safeChildren);
        }
        return out;
    }
    private JsonNode sanitizeMark(JsonNode mark) {
        if (!mark.isObject()) throw ApiException.badRequest("文字格式无效。");
        String type=mark.path("type").asText("");
        if (!MARK_TYPES.contains(type)) throw ApiException.badRequest("正文包含不支持的文字格式。");
        ObjectNode out=json.createObjectNode().put("type",type);
        JsonNode attrs=mark.get("attrs");
        if (attrs!=null && attrs.isObject()) {
            ObjectNode safe=json.createObjectNode();
            if (type.equals("link")) {
                String href=attrs.path("href").asText("");
                if (!safeLink(href)) throw ApiException.badRequest("链接地址无效。");
                safe.put("href",href);
            } else if (type.equals("textStyle")) {
                String color=attrs.path("color").asText("");
                String size=attrs.path("fontSize").asText("");
                String family=attrs.path("fontFamily").asText("");
                if (color.matches("#[0-9A-Fa-f]{6}")) safe.put("color",color);
                if (Set.of("16px","18px","22px","28px").contains(size)) safe.put("fontSize",size);
                if (Set.of("serif","sans-serif","monospace").contains(family)) safe.put("fontFamily",family);
            }
            if (safe.size()>0) out.set("attrs",safe);
        }
        return out;
    }
    private boolean safeLink(String href) {
        if (href.length()>2048) return false;
        try {
            java.net.URI uri=java.net.URI.create(href);
            String scheme=uri.getScheme();
            return scheme!=null && Set.of("http","https","mailto").contains(scheme.toLowerCase(Locale.ROOT));
        } catch (RuntimeException e) { return false; }
    }
    private String bounded(String value,int max) { return value.length()>max?value.substring(0,max):value; }
    private boolean hasVisibleText(String text) {
        return text.codePoints().anyMatch(code -> !Character.isWhitespace(code) && !Character.isSpaceChar(code) &&
                !Character.isISOControl(code) && Character.getType(code) != Character.FORMAT);
    }
    private boolean containsImage(JsonNode node) {
        if ("image".equals(node.path("type").asText())) return true;
        JsonNode children=node.path("content");
        if (children.isArray()) for(JsonNode child:children) if(containsImage(child)) return true;
        return false;
    }
    private String extractText(JsonNode node) {
        String type=node.path("type").asText();
        if (type.equals("text")) return node.path("text").asText();
        StringBuilder result=new StringBuilder();
        JsonNode children=node.path("content");
        if (children.isArray()) for(JsonNode child:children) {
            String part=extractText(child);
            if (!part.isEmpty()) {
                if (result.length()>0 && !type.equals("paragraph") && !type.equals("heading")) result.append('\n');
                result.append(part);
            }
        }
        return result.toString();
    }
    private static final class Counter { int nodes; }

    public record JournalRequest(LocalDate journalDate,String title,boolean automaticTitle,JsonNode content,
                                 String plainText,Long notebookId,List<Long> tagIds,Long expectedVersion,String clientId,
                                 String operationId, List<LocalDate> activityDates) {
        public JournalRequest(LocalDate journalDate, String title, boolean automaticTitle, JsonNode content,
                              String plainText, Long notebookId, List<Long> tagIds, Long expectedVersion, String clientId) {
            this(journalDate, title, automaticTitle, content, plainText, notebookId, tagIds, expectedVersion, clientId, null, null);
        }
    }
    public record JournalResponse(long id,LocalDate journalDate,String title,boolean automaticTitle,JsonNode content,
                                  String plainText,long version,Long notebookId,String notebookName,
                                  List<JournalMapper.TagRow> tags, Instant createdAt,
                                  Instant updatedAt, Instant deletedAt) {}
}
