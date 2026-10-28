package com.learningmaterial.system.mapper;

import com.learningmaterial.system.entity.ContentReviewRow;
import com.learningmaterial.system.entity.MaterialRow;
import com.learningmaterial.system.entity.UserRow;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ContentReviewMapper {
    @Select("SELECT * FROM app_user WHERE id=#{id} FOR SHARE")
    UserRow lockActor(long id);
    @Select("SELECT * FROM material WHERE id=#{id} FOR UPDATE")
    MaterialRow lockMaterial(long id);
    @Select("SELECT * FROM content_review WHERE id=#{id} FOR UPDATE")
    ContentReviewRow lockReview(long id);
    @Select("SELECT COUNT(*) FROM review_record WHERE content_review_id=#{review} AND operator_id=#{actor} AND session_id=#{session} AND action='CONTENT_VIEW'")
    long countContentViews(@Param("review")long review,@Param("actor")long actor,@Param("session")long session);
    @Insert("""
        INSERT INTO review_record(operator_id,target_user_id,content_review_id,material_id,session_id,action,reason,before_status,after_status,created_at)
        VALUES(#{actor},NULL,#{review},#{material},#{session},#{action},#{reason},#{before},#{after},#{now})
        """)
    @Options(useGeneratedKeys=true,keyProperty="audit.id")
    int audit(@Param("audit")AuditId audit,@Param("actor")long actor,@Param("review")long review,@Param("material")long material,@Param("session")long session,
              @Param("action")String action,@Param("reason")String reason,@Param("before")String before,@Param("after")String after,@Param("now")LocalDateTime now);
    class AuditId {
        private Long id;
        public Long getId(){return id;}
        public void setId(Long value){id=value;}
    }
    @Update("UPDATE content_review SET status=#{status},decision_reason=#{reason},version=version+1,decided_at=#{now} WHERE id=#{id} AND status='PENDING' AND version=#{version}")
    int decide(@Param("id")long id,@Param("version")int version,@Param("status")String status,@Param("reason")String reason,@Param("now")LocalDateTime now);
    @Update("UPDATE material SET access_state=#{state},updated_at=#{now} WHERE id=#{id}")
    int setAccess(@Param("id")long id,@Param("state")String state,@Param("now")LocalDateTime now);
    String VIEW="SELECT r.*,m.version current_material_version,m.status material_status FROM content_review r JOIN material m ON m.id=r.material_id ";
    @Insert("""
        INSERT INTO content_review(material_id,user_id,material_version,title,material_type,trigger_type,access_scope,status,version,reason,created_at)
        VALUES(#{materialId},#{userId},#{materialVersion},#{title},#{materialType},'USER_SUBMITTED','MATERIAL_CONTENT','PENDING',1,#{reason},#{createdAt})
        """)
    @Options(useGeneratedKeys=true,keyProperty="id")
    int insert(ContentReviewRow row);
    @Select(VIEW+"WHERE r.id=#{id}")
    ContentReviewRow find(long id);
    @Select("SELECT id FROM content_review WHERE material_id=#{materialId} AND material_version=#{version}")
    Long findVersion(@Param("materialId")long materialId,@Param("version")int version);
    @Select("<script>"+VIEW+"<where><if test='materialId != null'>r.material_id=#{materialId}</if><if test='status != null'>AND r.status=#{status}</if></where> ORDER BY r.created_at DESC,r.id DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<ContentReviewRow> list(@Param("materialId")Long materialId,@Param("status")String status,@Param("size")int size,@Param("offset")long offset);
    @Select("<script>SELECT COUNT(*) FROM content_review <where><if test='materialId != null'>material_id=#{materialId}</if><if test='status != null'>AND status=#{status}</if></where></script>")
    long count(@Param("materialId")Long materialId,@Param("status")String status);
}
