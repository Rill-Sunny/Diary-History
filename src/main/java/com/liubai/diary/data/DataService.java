package com.liubai.diary.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.liubai.diary.auth.AppUser;
import com.liubai.diary.auth.UserMapper;
import com.liubai.diary.config.ApiException;
import com.liubai.diary.journal.JournalMapper;
import com.liubai.diary.journal.JournalService;
import com.liubai.diary.journal.NotebookMapper;
import com.liubai.diary.journal.TagIconService;
import com.liubai.diary.journal.TagMapper;
import com.liubai.diary.media.MediaService;
import com.liubai.diary.reminder.ReminderService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class DataService {
    private static final long MAX_BACKUP_CHARS = 150_000_000;
    private static final DateTimeFormatter DATE_TITLE = DateTimeFormatter.ofPattern("yyyy年M月d日");

    private final DataMapper data;
    private final ImportBatchMapper batches;
    private final JournalMapper journalRows;
    private final JournalService journals;
    private final NotebookMapper notebooks;
    private final TagMapper tags;
    private final TagIconService tagIcons;
    private final UserMapper users;
    private final MediaService media;
    private final ReminderService reminders;
    private final ObjectMapper json;

    public DataService(DataMapper data, ImportBatchMapper batches, JournalMapper journalRows, JournalService journals,
                       NotebookMapper notebooks, TagMapper tags, TagIconService tagIcons, UserMapper users,
                       MediaService media, ReminderService reminders, ObjectMapper json) {
        this.data=data; this.batches=batches; this.journalRows=journalRows; this.journals=journals;
        this.notebooks=notebooks; this.tags=tags; this.tagIcons=tagIcons; this.users=users;
        this.media=media; this.reminders=reminders; this.json=json;
    }

    public List<DataMapper.ExportEntry> exportable(long ownerId) {
        return data.exportable(ownerId);
    }

    @Transactional(readOnly=true)
    public BackupDocument backup(long ownerId) {
        AppUser user=users.findById(ownerId);
        if(user==null) throw ApiException.notFound();
        String archiveId=data.archiveId(ownerId);
        Map<Long,String> origins=origins(ownerId);
        List<NotebookMapper.NotebookRow> books=notebooks.list(ownerId);
        List<TagMapper.TagRow> tagList=tags.list(ownerId);
        Set<Long> mediaIds=new HashSet<>();
        books.stream().map(NotebookMapper.NotebookRow::coverMediaId).filter(Objects::nonNull).forEach(mediaIds::add);
        tagList.stream().map(TagMapper.TagRow::iconMediaId).filter(Objects::nonNull).forEach(mediaIds::add);
        List<ObjectNode> entries=backupEntries(ownerId,false,archiveId,origins,mediaIds);
        List<ObjectNode> trash=backupEntries(ownerId,true,archiveId,origins,mediaIds);
        List<BackupActivity> activity=data.activity(ownerId).stream()
                .map(a->new BackupActivity(activityOrigin(archiveId,a),a.date(),a.type())).toList();
        List<MediaService.BackupMedia> assets=media.exportOwned(ownerId,mediaIds);
        return new BackupDocument(2,LocalDateTime.now(ZoneOffset.UTC),archiveId,
                new Preferences(user.getNickname(),user.getTimeZone(),user.getTheme(),user.getAccent(),reminders.schedule(ownerId)),
                books,tagList,entries,trash,assets,activity,
                new Manifest(entries.size(),trash.size(),assets.size(),activity.size()));
    }

    private List<ObjectNode> backupEntries(long ownerId, boolean trash, String archiveId, Map<Long,String> origins, Set<Long> mediaIds) {
        return journalRows.list(ownerId,trash,null,null,null,null,null,null,null,null,Integer.MAX_VALUE,0).stream().map(row->{
            JsonNode content=parseJson(row.contentJson());
            visitImages(content, attrs->mediaIds.add(attrs.path("mediaId").asLong()));
            ObjectNode item=json.createObjectNode();
            item.put("id",row.id()).put("sourceKey",origins.getOrDefault(row.id(),journalOrigin(archiveId,row.id())))
                .put("journalDate",row.journalDate().toString()).put("title",row.title())
                .put("automaticTitle",row.automaticTitle()).put("plainText",row.plainText())
                .put("createdAt",row.createdAt().toString()).put("updatedAt",row.updatedAt().toString());
            if(row.notebookId()!=null) item.put("notebookId",row.notebookId());
            if(row.deletedAt()!=null) item.put("deletedAt",row.deletedAt().toString());
            item.set("content",content);
            item.set("tags",json.valueToTree(journalRows.tagsFor(ownerId,row.id())));
            return item;
        }).toList();
    }

    @Transactional
    public ImportResult importJournals(long ownerId,List<JournalService.JournalRequest> entries,String operationId) {
        if(data.lockArchive(ownerId)==null) throw ApiException.notFound();
        String op="markdown:"+validOperation(operationId);
        ImportBatchMapper.BatchRow batch=startBatch(ownerId,op);
        if(done(batch)) return new ImportResult(batch.importedCount(),batch.skippedCount(),"该导入批次已经处理过。");
        ImportPlan plan=planImport(ownerId,entries);
        int index=0;
        for(JournalService.JournalRequest entry:plan.additions()) {
            String key="markdown_"+stable(op)+"_"+index++;
            journals.importOne(ownerId,new JournalService.JournalRequest(entry.journalDate(),entry.title(),entry.automaticTitle(),
                    entry.content(),entry.plainText(),entry.notebookId(),entry.tagIds(),null,key));
        }
        int imported=plan.additions().size(), skipped=plan.skipped();
        if(imported>0) journals.importActivity(ownerId,op);
        batches.finish(batch.id(),imported,skipped,0,0);
        return new ImportResult(imported,skipped,imported+" 篇已导入，重复项跳过 "+skipped+" 篇。");
    }

    @Transactional(readOnly=true)
    public ImportResult previewImport(long ownerId,List<JournalService.JournalRequest> entries) {
        ImportPlan plan=planImport(ownerId,entries);
        return new ImportResult(plan.additions().size(),plan.skipped(),"导入预览已完成。");
    }

    private ImportPlan planImport(long ownerId,List<JournalService.JournalRequest> entries) {
        Set<EntryIdentity> known=new HashSet<>();
        Map<LocalDate,List<JournalMapper.JournalRow>> byDate=new HashMap<>();
        List<JournalService.JournalRequest> additions=new ArrayList<>();
        int skipped=0;
        for(JournalService.JournalRequest entry:entries) {
            if(entry==null||entry.journalDate()==null||entry.content()==null) throw ApiException.badRequest("导入内容中有无效记录。");
            JsonNode content=parseJson(journals.normalizeImportContent(ownerId,entry.content()).toString());
            String title=titleFor(entry.title(),entry.automaticTitle(),entry.journalDate());
            Long notebookId=entry.notebookId()==null||entry.notebookId()<=0?notebooks.defaultId(ownerId):entry.notebookId();
            List<Long> tagIds=entry.tagIds()==null?List.of():entry.tagIds().stream().distinct().sorted().toList();
            EntryIdentity identity=new EntryIdentity(entry.journalDate(),title,notebookId,tagIds,content);
            List<JournalMapper.JournalRow> candidates=byDate.computeIfAbsent(entry.journalDate(),date->
                    journalRows.list(ownerId,false,date,null,null,null,null,null,null,null,Integer.MAX_VALUE,0));
            boolean duplicate=!known.add(identity)||candidates.stream().anyMatch(row->title.equals(row.title())
                    && Objects.equals(notebookId,row.notebookId()) && content.equals(parseJson(row.contentJson()))
                    && tagIds.equals(journalRows.tagsFor(ownerId,row.id()).stream().map(JournalMapper.TagRow::id).sorted().toList()));
            if(duplicate) { skipped++; continue; }
            additions.add(new JournalService.JournalRequest(entry.journalDate(),entry.title(),entry.automaticTitle(),
                    content,entry.plainText(),notebookId,tagIds,null,null));
        }
        return new ImportPlan(additions,skipped);
    }

    @Transactional(readOnly=true)
    public RestoreResult previewRestore(long ownerId,JsonNode backup,boolean mergeBooks) {
        return planRestore(ownerId,backup,mergeBooks).summary();
    }

    @Transactional
    public RestoreResult restore(long ownerId,JsonNode backup,boolean restoreSettings,boolean mergeBooks) {
        String archiveId=data.lockArchive(ownerId);
        if(archiveId==null) throw ApiException.notFound();
        RestorePlan plan=planRestore(ownerId,backup,mergeBooks);
        String op=operationFor(backup,mergeBooks);
        ImportBatchMapper.BatchRow batch=startBatch(ownerId,op);
        if(restoreSettings&&backup.path("preferences").isObject()) restorePreferences(ownerId,backup.path("preferences"));
        if(done(batch)) return plan.summary();

        Set<Long> mediaIds=new HashSet<>();
        for(PlannedEntry entry:plan.entries()) {
            visitImages(entry.source().path("content"),attrs->mediaIds.add(attrs.path("mediaId").asLong()));
        }
        collectAssetIds(backup.path("notebooks"),"coverMediaId",mediaIds);
        collectAssetIds(backup.path("tags"),"iconMediaId",mediaIds);
        Map<Long,Long> mediaMap=restoreMedia(ownerId,plan.assets(),mediaIds);
        Map<Long,Long> bookMap=restoreNotebooks(ownerId,backup.path("notebooks"),mediaMap,mergeBooks,plan.sourceArchive());
        Map<Long,Long> tagMap=restoreTags(ownerId,backup.path("tags"),mediaMap);
        for(PlannedEntry entry:plan.entries()) {
            JsonNode item=entry.source();
            JsonNode content=item.path("content").deepCopy();
            visitImages(content,attrs->{
                Long mapped=mediaMap.get(attrs.path("mediaId").asLong());
                if(mapped==null) throw ApiException.badRequest("备份引用的图片文件缺失，恢复已取消。");
                attrs.put("mediaId",mapped).put("src","/api/media/"+mapped).remove("offlineId");
            });
            Long notebookId=bookMap.getOrDefault(item.path("notebookId").asLong(),notebooks.defaultId(ownerId));
            List<Long> tagIds=new ArrayList<>();
            for(JsonNode tag:item.path("tags")) {
                Long tagId=tagMap.get(tag.path("id").asLong());
                if(tagId!=null) tagIds.add(tagId);
            }
            String title=item.path("title").asText();
            boolean automatic=item.path("automaticTitle").asBoolean(true);
            if(entry.conflict()) {
                title=titleFor(title,automatic,entry.date());
                if(title.length()>245) title=title.substring(0,245);
                title+="（恢复副本）";
                automatic=false;
            }
            String key="restore_"+sha256((entry.origin()+":"+entry.fingerprint()).getBytes(StandardCharsets.UTF_8));
            JournalService.JournalResponse saved=journals.importOne(ownerId,new JournalService.JournalRequest(
                    entry.date(),title,automatic,content,item.path("plainText").asText(),notebookId,tagIds,null,key));
            data.restoreMetadata(ownerId,saved.id(),entry.origin(),parseTimestamp(item.path("createdAt")),parseTimestamp(item.path("updatedAt")),entry.deletedAt());
        }
        restoreActivity(ownerId,archiveId,plan.sourceArchive(),backup.path("activity"),backup.path("schemaVersion").asInt());
        RestoreResult result=plan.summary();
        batches.finish(batch.id(),result.added(),result.skipped(),result.conflicts(),result.expired());
        return result;
    }

    private RestorePlan planRestore(long ownerId,JsonNode backup,boolean mergeBooks) {
        validateBackup(backup);
        Map<Long,Asset> assets=assets(backup.path("media"),backup.path("schemaVersion").asInt()==2);
        Map<Long,String> sourceHashes=new HashMap<>();
        assets.forEach((id,asset)->sourceHashes.put(id,asset.hash()));
        String archiveId=data.archiveId(ownerId);
        if(archiveId==null) throw ApiException.notFound();
        String sourceArchive=backup.path("sourceArchiveId").asText();
        if(sourceArchive.isBlank()) sourceArchive="legacy:"+backup.path("username").asText();
        if(sourceArchive.length()>80) throw ApiException.badRequest("备份来源标识无效。");

        List<JsonNode> sourceEntries=new ArrayList<>();
        backup.path("journals").forEach(sourceEntries::add);
        backup.path("trash").forEach(sourceEntries::add);
        List<String> expiredTitles=new ArrayList<>();
        List<PlannedEntry> pending=new ArrayList<>();
        Map<Long,String> origins=origins(ownerId);
        Map<Long,String> currentHashes=new HashMap<>();
        data.assetHashes(ownerId).forEach(asset->currentHashes.put(asset.id(),asset.sha256()));
        Set<String> localOrigins=new HashSet<>();
        Set<String> localContent=new HashSet<>();
        Set<String> recoveredContent=new HashSet<>();
        for(boolean trash:new boolean[]{false,true}) {
            for(JournalMapper.JournalRow row:journalRows.list(ownerId,trash,null,null,null,null,null,null,null,null,Integer.MAX_VALUE,0)) {
                String origin=origins.getOrDefault(row.id(),journalOrigin(archiveId,row.id()));
                localOrigins.add(origin);
                String fingerprint=fingerprint(row.journalDate(),row.title(),row.deletedAt(),parseJson(row.contentJson()),currentHashes);
                localContent.add(fingerprint);
                if(row.title().endsWith("（恢复副本）")) {
                    String originalTitle=row.title().substring(0,row.title().length()-6);
                    recoveredContent.add(origin+":"+fingerprint(row.journalDate(),originalTitle,row.deletedAt(),parseJson(row.contentJson()),currentHashes));
                }
            }
        }
        int skipped=0,conflicts=0,trashAdded=0;
        LocalDateTime expiryCutoff=LocalDateTime.now(ZoneOffset.UTC).minusDays(15);
        Set<Long> sourceIds=new HashSet<>();
        for(JsonNode item:sourceEntries) {
            long id=item.path("id").asLong(-1);
            if(id<=0||!sourceIds.add(id)) throw ApiException.badRequest("备份日记标识缺失或重复。");
            LocalDate date;
            try { date=LocalDate.parse(item.path("journalDate").asText()); }
            catch(RuntimeException e) { throw ApiException.badRequest("备份中包含无效日记日期。"); }
            LocalDateTime deletedAt=parseTimestamp(item.path("deletedAt"));
            if(deletedAt!=null&&!deletedAt.isAfter(expiryCutoff)) {
                expiredTitles.add(date+" · "+item.path("title").asText());
                continue;
            }
            if(!item.path("content").isObject()) throw ApiException.badRequest("备份中有日记正文无法识别。");
            String origin=item.path("sourceKey").asText(journalOrigin(sourceArchive,id));
            if(!origin.matches("[0-9a-f]{64}")) throw ApiException.badRequest("备份日记来源标识无效。");
            String title=titleFor(item.path("title").asText(),item.path("automaticTitle").asBoolean(true),date);
            String fingerprint=fingerprint(date,title,deletedAt,item.path("content"),sourceHashes);
            if(localContent.contains(fingerprint)||recoveredContent.contains(origin+":"+fingerprint)) { skipped++; continue; }
            boolean conflict=!localOrigins.add(origin);
            if(conflict) conflicts++;
            if(deletedAt!=null) trashAdded++;
            pending.add(new PlannedEntry(item,date,deletedAt,origin,fingerprint,conflict));
            localContent.add(fingerprint);
        }
        List<String> sameBooks=duplicateNotebookNames(ownerId,backup.path("notebooks"));
        List<String> changedTags=changedTagNames(ownerId,backup.path("tags"),sourceHashes,currentHashes);
        ImportBatchMapper.BatchRow prior=batches.find(ownerId,operationFor(backup,mergeBooks));
        if(prior!=null&&done(prior)) {
            skipped+=pending.size();
            pending.clear();
            conflicts=0;
            trashAdded=0;
        }
        RestoreResult summary=new RestoreResult(pending.size(),skipped,conflicts,expiredTitles.size(),
                trashAdded,List.copyOf(expiredTitles),sameBooks,changedTags);
        return new RestorePlan(sourceArchive,assets,pending,summary);
    }

    private void validateBackup(JsonNode backup) {
        if(backup==null||!backup.isObject()||!backup.path("journals").isArray())
            throw ApiException.badRequest("备份格式无法识别。");
        int version=backup.path("schemaVersion").asInt();
        if(version!=1&&version!=2) throw ApiException.badRequest("此版本无法读取该备份格式。");
        if(backup.toString().length()>MAX_BACKUP_CHARS) throw ApiException.badRequest("备份文件超过 150 MB。");
        if(backup.path("media").size()>10_000||backup.path("journals").size()+backup.path("trash").size()>20_000
                ||backup.path("activity").size()>200_000) throw ApiException.badRequest("备份条目数量超出限制。");
        if(version==2) {
            JsonNode manifest=backup.path("manifest");
            for(String field:List.of("journals","trash","media","activity")) {
                if(!backup.path(field).isArray()||manifest.path(field).asInt(-1)!=backup.path(field).size())
                    throw ApiException.badRequest("备份清单和数据数量不一致。");
            }
        }
    }

    private String operationFor(JsonNode backup,boolean mergeBooks) {
        return "restore:"+sha256(backup.toString().getBytes(StandardCharsets.UTF_8))+":"+(mergeBooks?"merge":"separate");
    }

    private Map<Long,Asset> assets(JsonNode list,boolean requireHash) {
        Map<Long,Asset> assets=new HashMap<>();
        for(JsonNode item:list) {
            long id=item.path("id").asLong();
            try {
                String encoded=item.path("data").asText();
                if(encoded.length()>16*1024*1024) throw ApiException.badRequest("备份中的单张图片超过 12 MB。");
                byte[] bytes=Base64.getDecoder().decode(encoded);
                String hash=sha256(bytes);
                if(bytes.length==0||id<=0||assets.putIfAbsent(id,new Asset(item.path("name").asText("image"),encoded,hash))!=null
                        ||(requireHash&&!hash.equals(item.path("sha256").asText()))) {
                    throw ApiException.badRequest("备份图片缺失、标识重复或校验失败。");
                }
            } catch(IllegalArgumentException e) {
                throw ApiException.badRequest("备份图片编码无效。");
            }
        }
        return assets;
    }

    private Map<Long,Long> restoreMedia(long ownerId,Map<Long,Asset> assets,Set<Long> ids) {
        Map<Long,Long> result=new HashMap<>();
        for(long id:ids) {
            Asset item=assets.get(id);
            if(item==null) throw ApiException.badRequest("备份中缺少引用的图片。");
            result.put(id,media.uploadBytes(ownerId,item.name(),Base64.getDecoder().decode(item.encoded())).id());
        }
        return result;
    }

    private List<String> duplicateNotebookNames(long ownerId,JsonNode books) {
        Set<String> names=new HashSet<>();
        notebooks.list(ownerId).forEach(book->names.add(book.name()));
        List<String> duplicates=new ArrayList<>();
        for(JsonNode item:books) {
            String name=item.path("name").asText();
            if(!item.path("isDefault").asBoolean()&&names.contains(name)) duplicates.add(name);
        }
        return duplicates;
    }

    private List<String> changedTagNames(long ownerId,JsonNode list,Map<Long,String> sourceHashes,Map<Long,String> currentHashes) {
        Map<String,TagMapper.TagRow> existing=new HashMap<>();
        tags.list(ownerId).forEach(tag->existing.put(tag.name(),tag));
        List<String> changed=new ArrayList<>();
        for(JsonNode item:list) {
            String name=item.path("name").asText();
            TagMapper.TagRow tag=existing.get(name);
            if(tag!=null&&(!sameTag(item,tag)||!Objects.equals(sourceHashes.get(item.path("iconMediaId").asLong()),currentHashes.get(tag.iconMediaId()))))
                changed.add(name);
        }
        return changed;
    }

    private Map<Long,Long> restoreNotebooks(long ownerId,JsonNode list,Map<Long,Long> mediaMap,boolean merge,String archive) {
        Map<Long,Long> result=new HashMap<>();
        List<NotebookMapper.NotebookRow> existing=new ArrayList<>(notebooks.list(ownerId));
        Long defaultId=notebooks.defaultId(ownerId);
        for(JsonNode item:list) {
            long source=item.path("id").asLong();
            String name=item.path("name").asText().strip();
            if(source<=0||name.isBlank()||name.length()>60) throw ApiException.badRequest("备份日记本名称或标识无效。");
            boolean isDefault=item.path("isDefault").asBoolean();
            NotebookMapper.NotebookRow match=findBook(existing,name);
            if(!isDefault&&!merge&&match!=null) {
                String suffix="（备份 "+sha256((archive+":"+source).getBytes(StandardCharsets.UTF_8)).substring(0,6)+"）";
                name=name.substring(0,Math.min(name.length(),60-suffix.length()))+suffix;
                match=findBook(existing,name);
            }
            Long mapped;
            if(isDefault) mapped=defaultId;
            else {
                if(match==null) {
                    notebooks.create(ownerId,name);
                    existing=new ArrayList<>(notebooks.list(ownerId));
                    match=findBook(existing,name);
                }
                mapped=match==null?null:match.id();
            }
            if(mapped==null) throw ApiException.badRequest("无法恢复备份中的日记本。");
            result.put(source,mapped);
            Long cover=mediaMap.get(item.path("coverMediaId").asLong());
            if(cover!=null&&(match==null||match.coverMediaId()==null)) notebooks.setCover(mapped,ownerId,cover);
        }
        return result;
    }

    private NotebookMapper.NotebookRow findBook(List<NotebookMapper.NotebookRow> rows,String name) {
        return rows.stream().filter(book->book.name().equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    private Map<Long,Long> restoreTags(long ownerId,JsonNode list,Map<Long,Long> mediaMap) {
        Map<Long,Long> result=new HashMap<>();
        for(JsonNode item:list) {
            long source=item.path("id").asLong();
            String name=item.path("name").asText().strip();
            if(source<=0||name.isBlank()||name.length()>40) throw ApiException.badRequest("备份标签名称或标识无效。");
            String color=tagColor(item);
            String icon=tagIcon(item);
            String style=tagStyle(item);
            Long asset=mediaMap.get(item.path("iconMediaId").asLong());
            TagMapper.TagRow row=tags.findByName(ownerId,name);
            if(row!=null&&(!sameTag(item,row)||!Objects.equals(asset,row.iconMediaId()))) {
                String suffix="（备份 "+sha256((color+":"+icon+":"+style+":"+asset).getBytes(StandardCharsets.UTF_8)).substring(0,6)+"）";
                name=name.substring(0,Math.min(name.length(),40-suffix.length()))+suffix;
                row=tags.findByName(ownerId,name);
            }
            if(row==null) {
                tags.insert(ownerId,name,color,icon);
                row=tags.findByName(ownerId,name);
                tags.setStyle(row.id(),ownerId,style);
                if(asset!=null) tagIcons.submitRestored(ownerId,row.id(),asset);
            }
            result.put(source,row.id());
        }
        return result;
    }

    private boolean sameTag(JsonNode item,TagMapper.TagRow row) {
        return tagColor(item).equalsIgnoreCase(row.color())&&tagIcon(item).equals(row.icon())&&tagStyle(item).equals(row.style());
    }
    private String tagColor(JsonNode item) {
        String color=item.path("color").asText();
        return color.matches("#[0-9A-Fa-f]{6}")?color:"#798744";
    }
    private String tagIcon(JsonNode item) {
        String icon=item.path("icon").asText("leaf");
        return Set.of("leaf","book","heart","star","tag","clock","sun","moon","coffee").contains(icon)?icon:"leaf";
    }
    private String tagStyle(JsonNode item) {
        String style=item.path("style").asText("soft");
        return Set.of("soft","outline","solid").contains(style)?style:"soft";
    }

    private void restorePreferences(long ownerId,JsonNode prefs) {
        String nickname=prefs.path("nickname").asText("日记用户").strip();
        if(nickname.isBlank()) nickname="日记用户";
        if(nickname.length()>40) nickname=nickname.substring(0,40);
        String zone=prefs.path("timeZone").asText("Asia/Shanghai");
        try { java.time.ZoneId.of(zone); } catch(RuntimeException e) { zone="Asia/Shanghai"; }
        String theme="dark".equals(prefs.path("theme").asText())?"dark":"light";
        String accent=Set.of("olive","blue","rose").contains(prefs.path("accent").asText())?prefs.path("accent").asText():"olive";
        users.updateSettings(ownerId,nickname,zone,theme,accent);
        if(prefs.path("reminderSchedule").isObject()) {
            try {
                reminders.restoreSchedule(ownerId,json.treeToValue(prefs.path("reminderSchedule"),ReminderService.Schedule.class));
            } catch(com.fasterxml.jackson.core.JsonProcessingException e) {
                throw ApiException.badRequest("备份中的提醒时间格式无效。");
            }
        }
    }

    private void restoreActivity(long ownerId,String archive,String source,JsonNode list,int version) {
        Set<String> known=new HashSet<>();
        for(DataMapper.Activity item:data.activity(ownerId)) known.add(activityOrigin(archive,item));
        for(JsonNode item:list) {
            LocalDate date;
            try { date=LocalDate.parse(item.path("date").asText()); } catch(RuntimeException e) {
                throw ApiException.badRequest("备份中的写作活动日期无效。");
            }
            if(version==1) {
                int count=item.path("count").asInt();
                if(count<0||count>2000) throw ApiException.badRequest("旧备份中的活动次数无效。");
                for(int i=0;i<count;i++) restoreActivityRow(ownerId,known,stable(source+":legacy:"+date+":"+i),date,"IMPORT");
            } else {
                String origin=item.path("sourceKey").asText();
                String type=item.path("type").asText();
                if(!origin.matches("[0-9a-f]{64}")||!type.matches("[A-Z_]{1,16}")) throw ApiException.badRequest("备份中的写作活动标识无效。");
                restoreActivityRow(ownerId,known,origin,date,type);
            }
        }
    }

    private void restoreActivityRow(long ownerId,Set<String> known,String origin,LocalDate date,String type) {
        if(!known.add(origin)) return;
        String key="backup:"+origin;
        if(journalRows.addActivity(ownerId,null,key,date,type)==1) data.restoreActivityOrigin(ownerId,key,origin);
    }

    private String activityOrigin(String archive,DataMapper.Activity item) {
        return item.archiveOrigin()==null?stable(archive+":activity:"+item.key()):item.archiveOrigin();
    }
    private String journalOrigin(String archive,long id) {
        return stable(archive+":journal:"+id);
    }
    private String stable(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }
    private Map<Long,String> origins(long ownerId) {
        Map<Long,String> result=new HashMap<>();
        for(DataMapper.Origin item:data.journalOrigins(ownerId)) result.put(item.id(),item.archiveOrigin());
        return result;
    }
    private String fingerprint(LocalDate date,String title,LocalDateTime deletedAt,JsonNode content,Map<Long,String> hashes) {
        JsonNode copy=content.deepCopy();
        visitImages(copy,attrs->{
            String hash=hashes.get(attrs.path("mediaId").asLong());
            if(hash==null) throw ApiException.badRequest("日记引用的图片缺失。");
            attrs.remove(List.of("mediaId","src","offlineId"));
            attrs.put("assetHash",hash);
        });
        return stable(date+"\n"+title+"\n"+deletedAt+"\n"+canonical(copy));
    }
    private JsonNode canonical(JsonNode node) {
        if(node.isObject()) {
            ObjectNode ordered=json.createObjectNode();
            TreeMap<String,JsonNode> fields=new TreeMap<>();
            node.fields().forEachRemaining(field->fields.put(field.getKey(),field.getValue()));
            fields.forEach((key,value)->ordered.set(key,canonical(value)));
            return ordered;
        }
        if(node.isArray()) {
            var ordered=json.createArrayNode();
            node.forEach(child->ordered.add(canonical(child)));
            return ordered;
        }
        return node;
    }
    private void visitImages(JsonNode node,java.util.function.Consumer<ObjectNode> visit) {
        if("image".equals(node.path("type").asText())&&node.get("attrs") instanceof ObjectNode attrs) visit.accept(attrs);
        for(JsonNode child:node.path("content")) visitImages(child,visit);
    }
    private void collectAssetIds(JsonNode list,String field,Set<Long> ids) {
        for(JsonNode item:list) if(item.hasNonNull(field)) ids.add(item.path(field).asLong());
    }
    private String titleFor(String title,boolean automatic,LocalDate date) {
        return automatic||title==null||title.isBlank()?date.format(DATE_TITLE):title.strip();
    }
    private LocalDateTime parseTimestamp(JsonNode value) {
        if(value.isMissingNode()||value.isNull()) return null;
        try { return LocalDateTime.parse(value.asText()); }
        catch(RuntimeException e) { throw ApiException.badRequest("备份中包含无效时间。"); }
    }
    private ImportBatchMapper.BatchRow startBatch(long ownerId,String operationId) {
        batches.start(ownerId,operationId);
        ImportBatchMapper.BatchRow row=batches.findForUpdate(ownerId,operationId);
        if(row==null) throw new IllegalStateException("Import batch could not be locked");
        return row;
    }
    private boolean done(ImportBatchMapper.BatchRow batch) {
        return batch.resultJson()!=null&&batch.resultJson().contains("DONE");
    }
    private String validOperation(String value) {
        if(value==null||value.length()<8||value.length()>100||!value.matches("[A-Za-z0-9:_-]+"))
            throw ApiException.badRequest("导入批次标识无效。");
        return value;
    }
    private String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    private JsonNode parseJson(String value) {
        try { return json.readTree(value); }
        catch(Exception e) { throw new IllegalStateException("Stored diary JSON is invalid",e); }
    }

    private record EntryIdentity(LocalDate date,String title,Long notebookId,List<Long> tagIds,JsonNode content) {}
    private record ImportPlan(List<JournalService.JournalRequest> additions,int skipped) {}
    private record Asset(String name,String encoded,String hash) {}
    private record PlannedEntry(JsonNode source,LocalDate date,LocalDateTime deletedAt,String origin,String fingerprint,boolean conflict) {}
    private record RestorePlan(String sourceArchive,Map<Long,Asset> assets,List<PlannedEntry> entries,RestoreResult summary) {}
    public record Preferences(String nickname,String timeZone,String theme,String accent,ReminderService.Schedule reminderSchedule) {}
    public record Manifest(int journals,int trash,int media,int activity) {}
    public record BackupActivity(String sourceKey,LocalDate date,String type) {}
    public record BackupDocument(int schemaVersion,LocalDateTime generatedAt,String sourceArchiveId,Preferences preferences,
                                 List<NotebookMapper.NotebookRow> notebooks,List<TagMapper.TagRow> tags,
                                 List<ObjectNode> journals,List<ObjectNode> trash,List<MediaService.BackupMedia> media,
                                 List<BackupActivity> activity,Manifest manifest) {}
    public record ImportResult(int imported,int skipped,String message) {}
    public record RestoreResult(int added,int skipped,int conflicts,int expired,int trashAdded,
                                List<String> expiredTitles,List<String> sameNotebooks,List<String> changedTags) {}
}
