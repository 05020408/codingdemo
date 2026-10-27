package cn.review.auth;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper {
    @Select("SELECT id,username,username_norm,password_hash,display_name,status FROM app_users WHERE username_norm=#{name}")
    UserRow byName(String name);
    @Insert("INSERT INTO app_users(id,username,username_norm,password_hash,display_name,status) VALUES(#{id},#{username},#{usernameNorm},#{passwordHash},#{displayName},#{status})")
    void insert(UserRow row);
}
