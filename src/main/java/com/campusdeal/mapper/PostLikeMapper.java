package com.campusdeal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campusdeal.entity.PostLike;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** Mapper for the durable post/user like relation. */
public interface PostLikeMapper extends BaseMapper<PostLike> {
    @Insert("INSERT IGNORE INTO tb_post_like(post_id, user_id, create_time) VALUES(#{postId}, #{userId}, CURRENT_TIMESTAMP)")
    int insertIgnore(@Param("postId") Long postId, @Param("userId") Long userId);

    @Delete("DELETE FROM tb_post_like WHERE post_id = #{postId} AND user_id = #{userId}")
    int deleteRelation(@Param("postId") Long postId, @Param("userId") Long userId);

    @Update("UPDATE tb_blog b LEFT JOIN (SELECT post_id, COUNT(*) AS like_count FROM tb_post_like GROUP BY post_id) l "
            + "ON l.post_id = b.id SET b.liked = COALESCE(l.like_count, 0)")
    int reconcilePostCounts();
}
