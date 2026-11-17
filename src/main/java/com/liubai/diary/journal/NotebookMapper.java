package com.liubai.diary.journal;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@org.apache.ibatis.annotations.Mapper
public interface NotebookMapper {
    record NotebookRow(long id, String name, boolean isDefault, long entryCount, Long coverMediaId, String coverUrl) {}

    @Insert("INSERT INTO notebooks (owner_id, name, is_default) VALUES (#{ownerId}, '日常', TRUE)")
    int insertDefault(@Param("ownerId") long ownerId);

    @Select("""
            SELECT n.id, n.name, n.is_default,
                   (SELECT COUNT(*) FROM journals j WHERE j.owner_id = n.owner_id AND j.notebook_id = n.id AND j.deleted_at IS NULL) AS entry_count,
                   n.cover_media_id, CONCAT('/api/media/', n.cover_media_id) AS cover_url
            FROM notebooks n
            WHERE n.owner_id = #{ownerId}
            ORDER BY n.is_default DESC, n.created_at ASC, n.id ASC
            """)
    List<NotebookRow> list(@Param("ownerId") long ownerId);

    @Select("SELECT id FROM notebooks WHERE id = #{id} AND owner_id = #{ownerId}")
    Long ownedId(@Param("id") long id, @Param("ownerId") long ownerId);

    @Select("SELECT id FROM notebooks WHERE owner_id = #{ownerId} AND is_default = TRUE LIMIT 1")
    Long defaultId(@Param("ownerId") long ownerId);

    @Insert("INSERT INTO notebooks (owner_id, name, is_default) VALUES (#{ownerId}, #{name}, FALSE)")
    int create(@Param("ownerId") long ownerId, @Param("name") String name);

    @Delete("DELETE FROM notebooks WHERE id = #{id} AND owner_id = #{ownerId} AND is_default = FALSE")
    int delete(@Param("id") long id, @Param("ownerId") long ownerId);

    @Update("UPDATE notebooks SET cover_media_id=#{mediaId} WHERE id=#{id} AND owner_id=#{ownerId}")
    int setCover(@Param("id") long id, @Param("ownerId") long ownerId, @Param("mediaId") Long mediaId);
}
