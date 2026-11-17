package com.liubai.diary.media;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface MediaMapper {
    class MediaRow {
        private long id;
        private long ownerId;
        private String sha256;
        private String originalName;
        private String mediaType;
        private long fileSize;
        private String storedName;
        public MediaRow() {}
        public MediaRow(long id, long ownerId, String sha256, String originalName, String mediaType, long fileSize, String storedName) {
            this.id=id; this.ownerId=ownerId; this.sha256=sha256; this.originalName=originalName;
            this.mediaType=mediaType; this.fileSize=fileSize; this.storedName=storedName;
        }
        public long getId() { return id; }
        public void setId(long value) { id=value; }
        public long getOwnerId() { return ownerId; }
        public void setOwnerId(long value) { ownerId=value; }
        public String getSha256() { return sha256; }
        public void setSha256(String value) { sha256=value; }
        public String getOriginalName() { return originalName; }
        public void setOriginalName(String value) { originalName=value; }
        public String getMediaType() { return mediaType; }
        public void setMediaType(String value) { mediaType=value; }
        public long getFileSize() { return fileSize; }
        public void setFileSize(long value) { fileSize=value; }
        public String getStoredName() { return storedName; }
        public void setStoredName(String value) { storedName=value; }
    }
    @Select("""
            SELECT id, owner_id AS ownerId, sha256, original_name AS originalName,
                   media_type AS mediaType, file_size AS fileSize, stored_name AS storedName
            FROM media_assets WHERE id = #{id} AND owner_id = #{ownerId}
            """)
    MediaRow findOwned(@Param("id") long id, @Param("ownerId") long ownerId);
    @Select("""
            SELECT id, owner_id AS ownerId, sha256, original_name AS originalName,
                   media_type AS mediaType, file_size AS fileSize, stored_name AS storedName
            FROM media_assets WHERE owner_id = #{ownerId} AND sha256 = #{sha256}
            """)
    MediaRow findByHash(@Param("ownerId") long ownerId, @Param("sha256") String sha256);
    @Select("SELECT id,owner_id AS ownerId,sha256,original_name AS originalName,media_type AS mediaType,file_size AS fileSize,stored_name AS storedName FROM media_assets WHERE owner_id=#{ownerId} ORDER BY id")
    List<MediaRow> listOwned(@Param("ownerId") long ownerId);
    @org.apache.ibatis.annotations.Delete("DELETE FROM media_assets WHERE id=#{id} AND owner_id=#{ownerId}")
    int deleteOwned(@Param("id") long id,@Param("ownerId") long ownerId);
    @Insert("""
            INSERT INTO media_assets (owner_id, sha256, original_name, media_type, file_size, stored_name)
            VALUES (#{ownerId}, #{sha256}, #{originalName}, #{mediaType}, #{fileSize}, #{storedName})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(MediaRow row);
}
