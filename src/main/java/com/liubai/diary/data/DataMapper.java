package com.liubai.diary.data;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface DataMapper {
    record ExportEntry(long id, LocalDate journalDate, String title, long notebookId, String excerpt) {}
    record Origin(long id, String archiveOrigin) {}
    record Activity(String key, LocalDate date, String type, String archiveOrigin) {}
    record AssetHash(long id, String sha256) {}

    @Select("SELECT id, sha256 FROM media_assets WHERE owner_id=#{ownerId}")
    List<AssetHash> assetHashes(@Param("ownerId") long ownerId);

    @Select("SELECT archive_id FROM app_users WHERE id=#{ownerId}")
    String archiveId(@Param("ownerId") long ownerId);

    @Select("SELECT archive_id FROM app_users WHERE id=#{ownerId} AND enabled=TRUE FOR UPDATE")
    String lockArchive(@Param("ownerId") long ownerId);

    @Select("""
            SELECT id, journal_date AS journalDate, title, COALESCE(notebook_id,0) AS notebookId, LEFT(plain_text, 90) AS excerpt
            FROM journals WHERE owner_id=#{ownerId} AND deleted_at IS NULL
            ORDER BY journal_date DESC, updated_at DESC, id DESC
            """)
    List<ExportEntry> exportable(@Param("ownerId") long ownerId);

    @Select("SELECT id, archive_origin AS archiveOrigin FROM journals WHERE owner_id=#{ownerId} AND archive_origin IS NOT NULL")
    List<Origin> journalOrigins(@Param("ownerId") long ownerId);

    @Update("""
            UPDATE journals SET archive_origin=#{origin}, created_at=COALESCE(#{createdAt},created_at),
                updated_at=COALESCE(#{updatedAt},updated_at), deleted_at=#{deletedAt}
            WHERE id=#{id} AND owner_id=#{ownerId}
            """)
    int restoreMetadata(@Param("ownerId") long ownerId, @Param("id") long id, @Param("origin") String origin,
                        @Param("createdAt") LocalDateTime createdAt, @Param("updatedAt") LocalDateTime updatedAt,
                        @Param("deletedAt") LocalDateTime deletedAt);

    @Select("""
            SELECT activity_key AS `key`, activity_date AS date, activity_type AS type, archive_origin AS archiveOrigin
            FROM writing_activity WHERE owner_id=#{ownerId} ORDER BY id
            """)
    List<Activity> activity(@Param("ownerId") long ownerId);

    @Update("""
            UPDATE writing_activity SET archive_origin=#{origin}
            WHERE owner_id=#{ownerId} AND activity_key=#{key}
            """)
    int restoreActivityOrigin(@Param("ownerId") long ownerId, @Param("key") String key, @Param("origin") String origin);
}
