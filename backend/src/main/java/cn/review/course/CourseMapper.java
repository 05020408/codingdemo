package cn.review.course;

import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface CourseMapper {
    String COLUMNS="id,owner_id,name,remark,status,lifecycle_version,row_version,created_at,updated_at";
    String SEARCH="owner_id=#{owner} AND status='ACTIVE' AND (#{q}='' OR LOCATE(#{q},name)>0 OR LOCATE(#{q},COALESCE(remark,''))>0)";
    @Select("SELECT "+COLUMNS+" FROM courses WHERE "+SEARCH+" ORDER BY updated_at DESC,id DESC LIMIT #{size} OFFSET #{offset}")
    List<CourseRow> list(@Param("owner")String owner,@Param("offset")int offset,@Param("size")int size,@Param("q")String q);
    @Select("SELECT COUNT(*) FROM courses WHERE "+SEARCH)
    long count(@Param("owner")String owner,@Param("q")String q);
    @Select("SELECT "+COLUMNS+" FROM courses WHERE id=#{id} AND owner_id=#{owner}")
    CourseRow byOwner(@Param("id")String id,@Param("owner")String owner);
    @Select("SELECT "+COLUMNS+" FROM courses WHERE id=#{id} AND owner_id=#{owner} FOR UPDATE")
    CourseRow lock(@Param("id")String id,@Param("owner")String owner);
    @Insert("INSERT INTO courses(id,owner_id,name,remark) VALUES(#{id},#{owner},#{name},#{remark})")
    void insert(@Param("id")String id,@Param("owner")String owner,@Param("name")String name,@Param("remark")String remark);
    @Update("UPDATE courses SET name=#{name},remark=#{remark},row_version=row_version+1,updated_at=CURRENT_TIMESTAMP(3) WHERE id=#{id} AND owner_id=#{owner} AND status='ACTIVE' AND row_version=#{version}")
    int edit(@Param("id")String id,@Param("owner")String owner,@Param("name")String name,@Param("remark")String remark,@Param("version")long version);
    @Update("UPDATE courses SET status='DELETING',lifecycle_version=lifecycle_version+1,row_version=row_version+1,deleted_at=CURRENT_TIMESTAMP(3),updated_at=CURRENT_TIMESTAMP(3) WHERE id=#{id} AND owner_id=#{owner} AND status='ACTIVE' AND row_version=#{version}")
    int markDeleting(@Param("id")String id,@Param("owner")String owner,@Param("version")long version);
}
