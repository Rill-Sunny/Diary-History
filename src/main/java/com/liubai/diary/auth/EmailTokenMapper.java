package com.liubai.diary.auth;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@org.apache.ibatis.annotations.Mapper
public interface EmailTokenMapper {
    record TokenRow(long id, long userId, String purpose, String targetEmail) {}

    @Insert("INSERT INTO email_tokens (user_id, purpose, token_hash, expires_at, target_email) VALUES (#{userId}, #{purpose}, #{hash}, #{expiresAt}, #{targetEmail,jdbcType=VARCHAR})")
    int create(@Param("userId") long userId,
               @Param("purpose") String purpose,
               @Param("hash") String hash,
               @Param("expiresAt") java.time.Instant expiresAt,
               @Param("targetEmail") String targetEmail);

    @Select("SELECT id, user_id AS userId, purpose, target_email AS targetEmail FROM email_tokens WHERE token_hash = #{hash} AND purpose = #{purpose} AND used_at IS NULL AND expires_at > CURRENT_TIMESTAMP(3) LIMIT 1")
    TokenRow findValid(@Param("hash") String hash, @Param("purpose") String purpose);

    @Update("UPDATE email_tokens SET used_at = CURRENT_TIMESTAMP(3) WHERE id = #{id} AND used_at IS NULL AND expires_at > CURRENT_TIMESTAMP(3)")
    int markUsed(@Param("id") long id);

    @Update("UPDATE email_tokens SET used_at=CURRENT_TIMESTAMP(3) WHERE user_id=#{userId} AND purpose=#{purpose} AND used_at IS NULL")
    int invalidate(@Param("userId") long userId, @Param("purpose") String purpose);

    @Select("SELECT id FROM email_tokens WHERE user_id=#{userId} AND purpose=#{purpose} AND created_at>DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 MINUTE) LIMIT 1 FOR UPDATE")
    Long recentlyIssuedId(@Param("userId") long userId, @Param("purpose") String purpose);

    default boolean issuedRecently(long userId, String purpose) {
        return recentlyIssuedId(userId, purpose) != null;
    }
}
