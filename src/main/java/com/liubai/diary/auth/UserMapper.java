package com.liubai.diary.auth;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@org.apache.ibatis.annotations.Mapper
public interface UserMapper {
    String COLUMNS = "id, email, username, nickname, password_hash AS passwordHash, role, enabled, " +
            "email_verified AS emailVerified, time_zone AS timeZone, theme, accent, auth_version AS authVersion, avatar_png IS NOT NULL AS hasAvatar";

    @Select("SELECT " + COLUMNS + " FROM app_users WHERE id = #{id}")
    AppUser findById(@Param("id") long id);

    @Select("SELECT " + COLUMNS + " FROM app_users WHERE id = #{id} FOR UPDATE")
    AppUser lockById(@Param("id") long id);

    @Select("SELECT " + COLUMNS + " FROM app_users WHERE email_normalized = LOWER(#{identity}) OR username_normalized = LOWER(#{identity}) LIMIT 1")
    AppUser findByLogin(@Param("identity") String identity);

    @Select("SELECT " + COLUMNS + " FROM app_users WHERE email_normalized = LOWER(#{email}) LIMIT 1")
    AppUser findByEmail(@Param("email") String email);

    @Select("SELECT " + COLUMNS + " FROM app_users WHERE username_normalized = LOWER(#{username}) LIMIT 1")
    AppUser findByUsername(@Param("username") String username);

    @Insert("""
            INSERT INTO app_users
              (email, email_normalized, username, username_normalized, nickname, password_hash, role, enabled, email_verified, time_zone)
            VALUES
              (#{email}, LOWER(#{email}), #{username}, LOWER(#{username}), #{nickname}, #{passwordHash},
               #{role}, #{accountEnabled}, #{emailVerified}, #{timeZone})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AppUser user);

    @Update("UPDATE app_users SET email_verified = TRUE, updated_at = CURRENT_TIMESTAMP WHERE id = #{id}")
    int verifyEmail(@Param("id") long id);

    @Update("UPDATE app_users SET email=#{email}, email_normalized=LOWER(#{email}) WHERE id=#{id} AND enabled=TRUE")
    int updateEmail(@Param("id") long id, @Param("email") String email);

    record AvatarRow(byte[] png) {}

    @Select("SELECT avatar_png AS png FROM app_users WHERE id=#{id} AND enabled=TRUE AND avatar_png IS NOT NULL")
    AvatarRow readAvatar(@Param("id") long id);

    @Update("UPDATE app_users SET avatar_png=#{png,jdbcType=BLOB} WHERE id=#{id} AND enabled=TRUE")
    int updateAvatar(@Param("id") long id, @Param("png") byte[] png);

    @Update("UPDATE app_users SET nickname = #{nickname}, time_zone = #{timeZone}, theme=#{theme}, accent=#{accent}, updated_at = CURRENT_TIMESTAMP WHERE id = #{id} AND enabled = TRUE")
    int updateSettings(@Param("id") long id, @Param("nickname") String nickname, @Param("timeZone") String timeZone,
                       @Param("theme") String theme, @Param("accent") String accent);

    @Update("UPDATE app_users SET password_hash = #{passwordHash}, auth_version = auth_version + 1, updated_at = CURRENT_TIMESTAMP WHERE id = #{id}")
    int updatePassword(@Param("id") long id, @Param("passwordHash") String passwordHash);

    @Update("UPDATE app_users SET enabled = FALSE, auth_version = auth_version + 1, updated_at = CURRENT_TIMESTAMP WHERE id = #{id}")
    int deactivate(@Param("id") long id);

    @Delete("DELETE FROM app_users WHERE id = #{id}")
    int delete(@Param("id") long id);
}
