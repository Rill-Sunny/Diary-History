package com.liubai.diary.journal;

import com.liubai.diary.media.MediaMapper;
import org.apache.ibatis.annotations.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface JournalMapper {
    record JournalRow(long id, LocalDate journalDate, String title, boolean automaticTitle,
                      String contentJson, String plainText, long version, Long notebookId,
                      String notebookName, LocalDateTime createdAt, LocalDateTime updatedAt,
                      LocalDateTime deletedAt) {}
    record TagRow(long id, String name, String color, String icon, String style, Long approvedIconMediaId) {}
    record SaveOperation(Long journalId, long journalVersion, String requestHash) {}
    String COLUMNS = """
            j.id, j.journal_date AS journalDate, j.title, j.automatic_title AS automaticTitle,
            CAST(j.content_json AS CHAR) AS contentJson, j.plain_text AS plainText, j.version,
            j.notebook_id AS notebookId, n.name AS notebookName, j.created_at AS createdAt,
            j.updated_at AS updatedAt, j.deleted_at AS deletedAt
            """;
    @Select("SELECT " + COLUMNS + " FROM journals j LEFT JOIN notebooks n ON n.id=j.notebook_id WHERE j.id=#{id} AND j.owner_id=#{ownerId} AND j.deleted_at IS NULL")
    JournalRow findOwned(@Param("id") long id, @Param("ownerId") long ownerId);
    @Select("SELECT " + COLUMNS + " FROM journals j LEFT JOIN notebooks n ON n.id=j.notebook_id WHERE j.id=#{id} AND j.owner_id=#{ownerId}")
    JournalRow findAnyOwned(@Param("id") long id, @Param("ownerId") long ownerId);
    @Select("SELECT " + COLUMNS + " FROM journals j LEFT JOIN notebooks n ON n.id=j.notebook_id WHERE j.owner_id=#{ownerId} AND j.client_id=#{clientId}")
    JournalRow findByClient(@Param("ownerId") long ownerId, @Param("clientId") String clientId);
    @Select("SELECT id FROM app_users WHERE id=#{ownerId} AND enabled=TRUE FOR UPDATE")
    Long lockOwner(@Param("ownerId") long ownerId);
    @Select("SELECT journal_id,journal_version,request_hash FROM journal_save_operations WHERE owner_id=#{ownerId} AND operation_id=#{operationId}")
    SaveOperation findSaveOperation(@Param("ownerId") long ownerId, @Param("operationId") String operationId);
    @Insert("INSERT INTO journal_save_operations(owner_id,operation_id,journal_id,journal_version,request_hash) VALUES(#{ownerId},#{operationId},#{journalId},#{version},#{hash})")
    void addSaveOperation(@Param("ownerId") long ownerId, @Param("operationId") String operationId,
                          @Param("journalId") long journalId, @Param("version") long version, @Param("hash") String hash);
    @Select("""
            <script>
            SELECT DISTINCT
            """ + COLUMNS + """
            FROM journals j LEFT JOIN notebooks n ON n.id=j.notebook_id
            LEFT JOIN journal_tags jt ON jt.journal_id=j.id AND jt.owner_id=j.owner_id
            WHERE j.owner_id=#{ownerId}
            <choose><when test='trash'>AND j.deleted_at IS NOT NULL AND j.deleted_at &gt; CURRENT_TIMESTAMP(3) - INTERVAL 15 DAY</when><otherwise>AND j.deleted_at IS NULL</otherwise></choose>
            <if test='date != null'>AND j.journal_date=#{date}</if>
            <if test='monthDay != null'>AND DATE_FORMAT(j.journal_date, '%m-%d')=#{monthDay}</if>
            <if test='from != null'>AND j.journal_date &gt;= #{from}</if>
            <if test='to != null'>AND j.journal_date &lt;= #{to}</if>
            <if test='notebookId != null'>AND j.notebook_id=#{notebookId}</if>
            <if test='tagId != null'>AND jt.tag_id=#{tagId}</if>
            <if test='q != null and q != ""'>AND (j.title LIKE CONCAT('%', #{q}, '%') ESCAPE '=' OR j.plain_text LIKE CONCAT('%', #{q}, '%') ESCAPE '=')</if>
            ORDER BY <choose><when test='sort == "date-asc"'>j.journal_date ASC, j.updated_at ASC, j.id ASC</when><otherwise>j.journal_date DESC, j.updated_at DESC, j.id DESC</otherwise></choose>
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<JournalRow> list(@Param("ownerId") long ownerId, @Param("trash") boolean trash,
                          @Param("date") LocalDate date, @Param("monthDay") String monthDay,
                          @Param("from") LocalDate from, @Param("to") LocalDate to,
                          @Param("notebookId") Long notebookId, @Param("tagId") Long tagId,
                          @Param("q") String q, @Param("sort") String sort, @Param("limit") int limit, @Param("offset") int offset);
    @Insert("""
            INSERT INTO journals (owner_id, notebook_id, client_id, journal_date, title, automatic_title, content_json, plain_text)
            VALUES (#{ownerId}, #{notebookId}, #{clientId}, #{journalDate}, #{title}, #{automaticTitle},
                    CAST(#{contentJson} AS JSON), #{plainText})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(NewJournal row);
    @Update("""
            UPDATE journals SET notebook_id=#{notebookId}, journal_date=#{journalDate},
              title=#{title}, automatic_title=#{automaticTitle}, content_json=CAST(#{contentJson} AS JSON),
              plain_text=#{plainText}, version=version+1
            WHERE id=#{id} AND owner_id=#{ownerId} AND deleted_at IS NULL AND version=#{expectedVersion}
            """)
    int update(UpdateJournal row);
    @Update("""
            UPDATE journals SET deleted_at=CURRENT_TIMESTAMP(3), version=version+1
            WHERE id=#{id} AND owner_id=#{ownerId} AND deleted_at IS NULL
                AND (#{version} IS NULL OR version=#{version})
            """)
    int trash(@Param("id") long id, @Param("ownerId") long ownerId, @Param("version") Long version);
    @Update("""
            UPDATE journals SET deleted_at=NULL,
              notebook_id=COALESCE(notebook_id,(SELECT id FROM notebooks WHERE owner_id=#{ownerId} AND is_default=TRUE LIMIT 1)),
              version=version+1
            WHERE id=#{id} AND owner_id=#{ownerId} AND deleted_at > CURRENT_TIMESTAMP(3) - INTERVAL 15 DAY
            """)
    int restore(@Param("id") long id, @Param("ownerId") long ownerId);
    @Delete("DELETE FROM journals WHERE id=#{id} AND owner_id=#{ownerId} AND deleted_at IS NOT NULL")
    int deletePermanently(@Param("id") long id, @Param("ownerId") long ownerId);
    @Select("SELECT t.id,t.name,t.color,t.icon_name AS icon,t.chip_style AS style,t.icon_media_id AS approvedIconMediaId FROM tags t JOIN journal_tags jt ON jt.tag_id=t.id WHERE jt.owner_id=#{ownerId} AND t.owner_id=#{ownerId} AND jt.journal_id=#{journalId} ORDER BY t.name")
    List<TagRow> tagsFor(@Param("ownerId") long ownerId, @Param("journalId") long journalId);
    @Delete("DELETE FROM journal_tags WHERE owner_id=#{ownerId} AND journal_id=#{journalId}")
    int clearTags(@Param("ownerId") long ownerId, @Param("journalId") long journalId);
    @Insert("INSERT INTO journal_tags (owner_id,journal_id,tag_id) VALUES (#{ownerId},#{journalId},#{tagId})")
    int addTag(@Param("ownerId") long ownerId, @Param("journalId") long journalId, @Param("tagId") long tagId);
    @Select("<script>SELECT COUNT(*) FROM tags WHERE owner_id=#{ownerId} AND id IN <foreach item='id' collection='ids' open='(' separator=',' close=')'>#{id}</foreach></script>")
    int countOwnedTags(@Param("ownerId") long ownerId, @Param("ids") List<Long> ids);
    @Insert("""
            INSERT IGNORE INTO writing_activity(owner_id,journal_id,activity_key,activity_date,activity_type)
            VALUES(#{ownerId},#{journalId},#{activityKey},#{activityDate},#{activityType})
            """)
    int addActivity(@Param("ownerId") long ownerId, @Param("journalId") Long journalId,
                    @Param("activityKey") String activityKey, @Param("activityDate") LocalDate activityDate,
                    @Param("activityType") String activityType);
    @Select("SELECT activity_date AS date, COUNT(*) AS count FROM writing_activity WHERE owner_id=#{ownerId} GROUP BY activity_date ORDER BY activity_date DESC")
    List<ActivityRow> activity(@Param("ownerId") long ownerId);
    @Select("""
            SELECT journal_date AS date, COUNT(*) AS count FROM journals
            WHERE owner_id=#{ownerId} AND deleted_at IS NULL AND journal_date BETWEEN #{from} AND #{to}
            GROUP BY journal_date ORDER BY journal_date
            """)
    List<ActivityRow> coverage(@Param("ownerId") long ownerId, @Param("from") LocalDate from, @Param("to") LocalDate to);
    record ActivityRow(LocalDate date, int count) {}
    @Update("UPDATE journals SET deleted_at=CURRENT_TIMESTAMP(3),version=version+1 WHERE owner_id=#{ownerId} AND notebook_id=#{notebookId} AND deleted_at IS NULL")
    int trashNotebookEntries(@Param("ownerId") long ownerId, @Param("notebookId") long notebookId);
    @Update("UPDATE share_links SET revoked_at=CURRENT_TIMESTAMP(3) WHERE journal_id=#{journalId} AND revoked_at IS NULL")
    int revokeJournalLinks(@Param("journalId") long journalId);
    @Update("UPDATE share_requests SET status='REVOKED',reviewed_at=CURRENT_TIMESTAMP(3),review_reason='Journal moved to trash' WHERE journal_id=#{journalId} AND status IN ('PENDING','APPROVED')")
    int revokeJournalRequests(@Param("journalId") long journalId);
    @Update("UPDATE share_links l JOIN journals j ON j.id=l.journal_id SET l.revoked_at=CURRENT_TIMESTAMP(3) WHERE j.owner_id=#{ownerId} AND j.notebook_id=#{notebookId} AND j.deleted_at IS NOT NULL AND l.revoked_at IS NULL")
    int revokeNotebookLinks(@Param("ownerId") long ownerId,@Param("notebookId") long notebookId);
    @Update("UPDATE share_requests r JOIN journals j ON j.id=r.journal_id SET r.status='REVOKED',r.reviewed_at=CURRENT_TIMESTAMP(3),r.review_reason='Journal moved to trash' WHERE j.owner_id=#{ownerId} AND j.notebook_id=#{notebookId} AND j.deleted_at IS NOT NULL AND r.status IN ('PENDING','APPROVED')")
    int revokeNotebookRequests(@Param("ownerId") long ownerId,@Param("notebookId") long notebookId);
    @Delete("DELETE FROM journals WHERE deleted_at <= CURRENT_TIMESTAMP(3) - INTERVAL 15 DAY")
    int purgeExpiredTrash();
    @Select("""
            SELECT m.id,m.owner_id AS ownerId,m.sha256,m.original_name AS originalName,m.media_type AS mediaType,
                   m.file_size AS fileSize,m.stored_name AS storedName
            FROM media_assets m
            WHERE (#{ownerId} IS NULL OR m.owner_id=#{ownerId})
            AND m.created_at < CURRENT_TIMESTAMP(3) - INTERVAL 1 DAY
            AND NOT EXISTS (SELECT 1 FROM notebooks n WHERE n.cover_media_id=m.id)
            AND NOT EXISTS (SELECT 1 FROM tags t WHERE t.icon_media_id=m.id)
            AND NOT EXISTS (SELECT 1 FROM tag_icon_reviews r WHERE r.media_id=m.id)
            AND NOT EXISTS (
              SELECT 1 FROM journals j
              WHERE j.owner_id=m.owner_id AND JSON_CONTAINS(JSON_EXTRACT(j.content_json, '$**.mediaId'), CAST(m.id AS JSON))
            )
            AND NOT EXISTS (
              SELECT 1 FROM share_requests r
              WHERE r.owner_id=m.owner_id AND JSON_CONTAINS(JSON_EXTRACT(r.content_snapshot, '$**.mediaId'), CAST(m.id AS JSON))
            )
            """)
    List<MediaMapper.MediaRow> orphanedMedia(@Param("ownerId") Long ownerId);
    class NewJournal {
        private long id, ownerId, notebookId;
        private String clientId, title, contentJson, plainText;
        private LocalDate journalDate;
        private boolean automaticTitle;
        public long getId(){return id;} public void setId(long v){id=v;}
        public long getOwnerId(){return ownerId;} public void setOwnerId(long v){ownerId=v;}
        public long getNotebookId(){return notebookId;} public void setNotebookId(long v){notebookId=v;}
        public String getClientId(){return clientId;} public void setClientId(String v){clientId=v;}
        public String getTitle(){return title;} public void setTitle(String v){title=v;}
        public String getContentJson(){return contentJson;} public void setContentJson(String v){contentJson=v;}
        public String getPlainText(){return plainText;} public void setPlainText(String v){plainText=v;}
        public LocalDate getJournalDate(){return journalDate;} public void setJournalDate(LocalDate v){journalDate=v;}
        public boolean isAutomaticTitle(){return automaticTitle;} public void setAutomaticTitle(boolean v){automaticTitle=v;}
    }
    class UpdateJournal extends NewJournal {
        private long expectedVersion;
        public long getExpectedVersion(){return expectedVersion;} public void setExpectedVersion(long v){expectedVersion=v;}
    }
}
