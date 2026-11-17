package com.liubai.diary.journal;

import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface TagIconMapper {
    record Review(long id, long tagId, long mediaId, long ownerId, String tagName, String submitter,
                  LocalDateTime submittedAt, String status, String reason) {}

    String REVIEWS = """
            SELECT r.id,r.tag_id,r.media_id,t.owner_id,t.name AS tag_name,u.username AS submitter,
                   r.submitted_at,r.status,r.reason
            FROM tag_icon_reviews r JOIN tags t ON t.id=r.tag_id JOIN app_users u ON u.id=t.owner_id
            """;

    @Select(REVIEWS + " WHERE r.id=#{id} AND u.enabled=TRUE")
    Review find(@Param("id") long id);

    @Select(REVIEWS + " WHERE u.enabled=TRUE ORDER BY (r.status='PENDING') DESC,r.submitted_at DESC,r.id DESC")
    List<Review> list();

    @Update("UPDATE tag_icon_reviews SET status='SUPERSEDED', reviewed_at=CURRENT_TIMESTAMP(3) WHERE tag_id=#{tagId} AND status='PENDING'")
    void supersedePending(@Param("tagId") long tagId);

    @Insert("INSERT INTO tag_icon_reviews(tag_id,media_id) VALUES(#{tagId},#{mediaId})")
    void submit(@Param("tagId") long tagId, @Param("mediaId") long mediaId);

    @Update("""
            UPDATE tag_icon_reviews SET status=#{status}, reviewer_id=#{reviewerId},
              reason=#{reason}, reviewed_at=CURRENT_TIMESTAMP(3)
            WHERE id=#{id} AND status='PENDING'
            """)
    int decide(@Param("id") long id, @Param("reviewerId") long reviewerId,
               @Param("status") String status, @Param("reason") String reason);
}
