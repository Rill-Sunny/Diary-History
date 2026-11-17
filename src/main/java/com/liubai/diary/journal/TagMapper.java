package com.liubai.diary.journal;

import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface TagMapper {
    record TagRow(long id, String name, String color, String icon, String style, Long iconMediaId,
                  Long approvedIconMediaId, String iconStatus, String iconReason) {}
    String COLUMNS = """
            t.id,t.name,t.color,t.icon_name AS icon,t.chip_style AS style,
            COALESCE(t.icon_media_id, CASE WHEN r.status='PENDING' THEN r.media_id END) AS iconMediaId,
            t.icon_media_id AS approvedIconMediaId, COALESCE(r.status,'BUILT_IN') AS iconStatus,
            r.reason AS iconReason
            """;
    String LATEST_REVIEW = """
            LEFT JOIN tag_icon_reviews r ON r.id=(SELECT MAX(review.id) FROM tag_icon_reviews review WHERE review.tag_id=t.id)
            """;
    @Select("SELECT " + COLUMNS + " FROM tags t " + LATEST_REVIEW + " WHERE t.owner_id=#{ownerId} ORDER BY t.name,t.id")
    List<TagRow> list(@Param("ownerId") long ownerId);
    @Insert("INSERT INTO tags(owner_id,name,color,icon_name) VALUES(#{ownerId},#{name},#{color},#{icon})")
    int insert(@Param("ownerId") long ownerId,@Param("name") String name,@Param("color") String color,@Param("icon") String icon);
    @Select("SELECT " + COLUMNS + " FROM tags t " + LATEST_REVIEW + " WHERE t.owner_id=#{ownerId} AND t.name=#{name}")
    TagRow findByName(@Param("ownerId") long ownerId,@Param("name") String name);
    @Delete("DELETE FROM tags WHERE owner_id=#{ownerId} AND id=#{id}")
    int delete(@Param("ownerId") long ownerId,@Param("id") long id);

    @Select("SELECT owner_id FROM tags WHERE id=#{id} FOR UPDATE")
    Long lockOwner(@Param("id") long id);

    @Update("UPDATE tags SET icon_media_id=#{mediaId}, icon_status='APPROVED' WHERE id=#{id}")
    void approveIcon(@Param("id") long id, @Param("mediaId") long mediaId);

    @Update("UPDATE tags SET chip_style=#{style} WHERE id=#{id} AND owner_id=#{ownerId}")
    void setStyle(@Param("id") long id, @Param("ownerId") long ownerId, @Param("style") String style);
}
