package com.liubai.diary.share;

import org.apache.ibatis.annotations.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ShareMapper {
    record RequestRow(long id,long ownerId,long journalId,long journalVersion,String journalTitle,
                      LocalDate journalDate,String contentJson,String plainText,int validityDays,
                      String status,LocalDateTime submittedAt,LocalDateTime reviewedAt,String reviewReason,
                      boolean updatesExistingLink) {}
    record LinkRow(long id,long ownerId,long journalId,String tokenValue,long currentRequestId,
                   LocalDateTime expiresAt,LocalDateTime revokedAt) {}
    record UserRequestRow(long id,long journalId,String journalTitle,String status,LocalDateTime submittedAt,
                          LocalDateTime expiresAt,String tokenValue,String reviewReason) {}
    String REQUEST_COLUMNS="""
            r.id,r.owner_id AS ownerId,r.journal_id AS journalId,r.journal_version AS journalVersion,
            r.title_snapshot AS journalTitle,r.journal_date_snapshot AS journalDate,
            CAST(r.content_snapshot AS CHAR) AS contentJson,r.plain_text_snapshot AS plainText,
            r.validity_days AS validityDays,r.status,r.submitted_at AS submittedAt,
            r.reviewed_at AS reviewedAt,r.review_reason AS reviewReason,r.updates_existing_link AS updatesExistingLink
            """;
    @Select("SELECT "+REQUEST_COLUMNS+" FROM share_requests r WHERE r.id=#{id}")
    RequestRow findById(@Param("id") long id);
    @Select("SELECT "+REQUEST_COLUMNS+" FROM share_requests r WHERE r.id=#{id} FOR UPDATE")
    RequestRow lockRequest(@Param("id") long id);
    @Select("SELECT id FROM app_users WHERE id=#{ownerId} AND enabled=TRUE FOR UPDATE")
    Long lockOwner(@Param("ownerId") long ownerId);
    @Select("SELECT id FROM journals WHERE id=#{id} AND owner_id=#{ownerId} AND deleted_at IS NULL FOR UPDATE")
    Long lockJournal(@Param("id") long id,@Param("ownerId") long ownerId);
    @Select("SELECT "+REQUEST_COLUMNS+" FROM share_requests r WHERE r.id=#{id} AND r.owner_id=#{ownerId}")
    RequestRow findOwned(@Param("id") long id,@Param("ownerId") long ownerId);
    @Select("SELECT "+REQUEST_COLUMNS+"""
            FROM share_requests r LEFT JOIN share_links l ON l.journal_id=r.journal_id
            WHERE r.status='PENDING' AND (r.updates_existing_link=FALSE OR (l.revoked_at IS NULL AND l.expires_at>CURRENT_TIMESTAMP))
            ORDER BY r.submitted_at,r.id LIMIT 500
            """)
    List<RequestRow> queue();
    @Select("SELECT "+REQUEST_COLUMNS+" FROM share_requests r WHERE r.journal_id=#{journalId} AND r.status='PENDING' ORDER BY r.id DESC LIMIT 1 FOR UPDATE")
    RequestRow newestPending(@Param("journalId") long journalId);
    @Update("UPDATE share_requests SET status='REVOKED',reviewed_at=CURRENT_TIMESTAMP(3),review_reason='Superseded by a newer submission' WHERE owner_id=#{ownerId} AND journal_id=#{journalId} AND status='PENDING'")
    int closeOlderPending(@Param("ownerId") long ownerId,@Param("journalId") long journalId);
    @Insert("""
            INSERT INTO share_requests(owner_id,journal_id,journal_version,title_snapshot,journal_date_snapshot,
                 content_snapshot,plain_text_snapshot,validity_days,status,updates_existing_link)
            VALUES(#{ownerId},#{journalId},#{journalVersion},#{title},#{journalDate},
                 CAST(#{contentJson} AS JSON),#{plainText},#{validityDays},'PENDING',#{updatesExistingLink})
            """)
    @Options(useGeneratedKeys=true,keyProperty="id")
    int insert(RequestInput request);
    @Select("""
            SELECT r.id,r.journal_id AS journalId,r.title_snapshot AS journalTitle,
                   CASE WHEN r.status='PENDING' AND r.updates_existing_link=TRUE
                                  AND (l.id IS NULL OR l.revoked_at IS NOT NULL OR l.expires_at<=CURRENT_TIMESTAMP) THEN 'EXPIRED'
                        WHEN r.status='APPROVED' AND l.current_request_id<>r.id THEN 'SUPERSEDED'
                        WHEN r.status='APPROVED' AND l.revoked_at IS NOT NULL THEN 'REVOKED'
                        WHEN r.status='APPROVED' AND l.expires_at<=CURRENT_TIMESTAMP THEN 'EXPIRED'
                        ELSE r.status END AS status,
                   r.submitted_at AS submittedAt,
                   CASE WHEN l.current_request_id=r.id AND l.revoked_at IS NULL AND l.expires_at>CURRENT_TIMESTAMP
                        THEN l.expires_at END AS expiresAt,
                   CASE WHEN l.current_request_id=r.id AND l.revoked_at IS NULL AND l.expires_at>CURRENT_TIMESTAMP
                        THEN l.token_value END AS tokenValue,
                   r.review_reason AS reviewReason
            FROM share_requests r LEFT JOIN share_links l ON l.journal_id=r.journal_id
            WHERE r.owner_id=#{ownerId} ORDER BY r.submitted_at DESC,r.id DESC LIMIT 500
            """)
    List<UserRequestRow> listOwner(@Param("ownerId") long ownerId);
    @Update("UPDATE share_requests SET status='APPROVED',reviewed_at=CURRENT_TIMESTAMP(3),reviewer_id=#{reviewerId},review_reason=NULL WHERE id=#{id} AND status='PENDING'")
    int approve(@Param("id") long id,@Param("reviewerId") long reviewerId);
    @Update("UPDATE share_requests SET status='REJECTED',reviewed_at=CURRENT_TIMESTAMP(3),reviewer_id=#{reviewerId},review_reason=#{reason} WHERE id=#{id} AND status='PENDING'")
    int reject(@Param("id") long id,@Param("reviewerId") long reviewerId,@Param("reason") String reason);
    @Select("SELECT id,owner_id AS ownerId,journal_id AS journalId,token_value AS tokenValue,current_request_id AS currentRequestId,expires_at AS expiresAt,revoked_at AS revokedAt FROM share_links WHERE journal_id=#{journalId} FOR UPDATE")
    LinkRow linkForJournal(@Param("journalId") long journalId);
    @Insert("INSERT INTO share_links(owner_id,journal_id,token_value,current_request_id,expires_at) VALUES(#{ownerId},#{journalId},#{tokenValue},#{requestId},#{expiresAt})")
    int insertLink(@Param("ownerId") long ownerId,@Param("journalId") long journalId,@Param("tokenValue") String token,
                   @Param("requestId") long requestId,@Param("expiresAt") LocalDateTime expiresAt);
    @Update("UPDATE share_links SET current_request_id=#{requestId} WHERE journal_id=#{journalId} AND revoked_at IS NULL AND expires_at>CURRENT_TIMESTAMP")
    int moveActiveLink(@Param("journalId") long journalId,@Param("requestId") long requestId);
    @Update("UPDATE share_links SET token_value=#{tokenValue},current_request_id=#{requestId},expires_at=#{expiresAt},revoked_at=NULL WHERE journal_id=#{journalId}")
    int renewLink(@Param("journalId") long journalId,@Param("tokenValue") String token,
                  @Param("requestId") long requestId,@Param("expiresAt") LocalDateTime expiresAt);
    @Update("UPDATE share_links SET revoked_at=CURRENT_TIMESTAMP(3) WHERE journal_id=#{journalId} AND revoked_at IS NULL")
    int revokeLink(@Param("journalId") long journalId);
    @Update("UPDATE share_requests SET status='REVOKED',reviewed_at=CURRENT_TIMESTAMP(3),review_reason='Revoked by owner' WHERE id=#{id} AND owner_id=#{ownerId} AND status IN ('PENDING','APPROVED')")
    int revokeRequest(@Param("id") long id,@Param("ownerId") long ownerId);
    @Select("""
            SELECT l.id,l.owner_id AS ownerId,l.journal_id AS journalId,l.token_value AS tokenValue,
                   l.current_request_id AS currentRequestId,l.expires_at AS expiresAt,l.revoked_at AS revokedAt
            FROM share_links l JOIN share_requests r ON r.id=l.current_request_id
              JOIN journals j ON j.id=l.journal_id JOIN app_users u ON u.id=l.owner_id
            WHERE l.token_value=#{token} AND l.revoked_at IS NULL AND l.expires_at>CURRENT_TIMESTAMP
              AND r.status='APPROVED' AND j.deleted_at IS NULL AND u.enabled=TRUE
            """)
    LinkRow activeLink(@Param("token") String token);
    @Select("SELECT "+REQUEST_COLUMNS+" FROM share_requests r WHERE r.id=#{requestId} AND r.status='APPROVED'")
    RequestRow approvedSnapshot(@Param("requestId") long requestId);
    class RequestInput {
        private long id,ownerId,journalId,journalVersion;
        private String title,contentJson,plainText;
        private LocalDate journalDate;
        private int validityDays;
        private boolean updatesExistingLink;
        public long getId(){return id;} public void setId(long v){id=v;}
        public long getOwnerId(){return ownerId;} public void setOwnerId(long v){ownerId=v;}
        public long getJournalId(){return journalId;} public void setJournalId(long v){journalId=v;}
        public long getJournalVersion(){return journalVersion;} public void setJournalVersion(long v){journalVersion=v;}
        public String getTitle(){return title;} public void setTitle(String v){title=v;}
        public String getContentJson(){return contentJson;} public void setContentJson(String v){contentJson=v;}
        public String getPlainText(){return plainText;} public void setPlainText(String v){plainText=v;}
        public LocalDate getJournalDate(){return journalDate;} public void setJournalDate(LocalDate v){journalDate=v;}
        public int getValidityDays(){return validityDays;} public void setValidityDays(int v){validityDays=v;}
        public boolean isUpdatesExistingLink(){return updatesExistingLink;}
        public void setUpdatesExistingLink(boolean value){updatesExistingLink=value;}
    }
}
