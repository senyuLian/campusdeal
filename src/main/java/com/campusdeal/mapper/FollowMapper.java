package com.campusdeal.mapper;

import com.campusdeal.entity.Follow;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface FollowMapper extends BaseMapper<Follow> {

    @Insert("INSERT IGNORE INTO tb_follow(user_id, follow_user_id, create_time) VALUES(#{userId}, #{followUserId}, CURRENT_TIMESTAMP)")
    int insertIgnore(@Param("userId") Long userId, @Param("followUserId") Long followUserId);

}
