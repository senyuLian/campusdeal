package com.campusdeal.service;

import com.campusdeal.dto.Result;
import com.campusdeal.entity.Post;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IPostService extends IService<Post> {

    Result queryHotBlog(Integer current);

    Result queryBlogById(Long id);

    Result likeBlog(Long id);

    /** Idempotent final-state transition: true likes, false unlikes. */
    Result likeBlog(Long id, Boolean isLike);

    Result queryBlogLikes(Long id);

    Result saveBlog(Post post);

    Result queryBlogOfFollow(Long max, Integer offset);

    /** Cursor based feed pagination; the cursor encodes the snapshot score and tie member. */
    Result queryBlogOfFollow(String cursor);
}
