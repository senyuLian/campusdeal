package com.campusdeal.controller;


import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campusdeal.dto.Result;
import com.campusdeal.dto.PostWriteRequest;
import com.campusdeal.dto.UserDTO;
import com.campusdeal.entity.Post;
import com.campusdeal.entity.Follow;
import com.campusdeal.entity.User;
import com.campusdeal.service.IPostService;
import com.campusdeal.service.IFollowService;
import com.campusdeal.service.IUserService;
import com.campusdeal.service.impl.FollowServiceImpl;
import com.campusdeal.utils.SystemConstants;
import com.campusdeal.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;

import jakarta.annotation.Resource;

import java.util.List;

import static com.campusdeal.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/post")
@Validated
public class PostController {

    @Resource
    private IPostService blogService;




    @PostMapping
    public Result saveBlog(@Valid @RequestBody PostWriteRequest request) {
        Post post = new Post().setShopId(request.getShopId()).setTitle(request.getTitle())
                .setContent(request.getContent()).setImages(request.getImages());
        return blogService.saveBlog(post);
    }

    @PutMapping("/like/{id}")
    public Result likeBlog(@PathVariable("id") Long id) {
        return blogService.likeBlog(id);
    }

    @PutMapping("/like/{id}/{isLike}")
    public Result setLike(@PathVariable("id") Long id, @PathVariable("isLike") Boolean isLike) {
        return blogService.likeBlog(id, isLike);
    }

    @GetMapping("/of/me")
    public Result queryMyBlog(@RequestParam(value = "current", defaultValue = "1") @Min(1) Integer current) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        // 根据用户查询
        Page<Post> page = blogService.query()
                .eq("user_id", user.getId()).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Post> records = page.getRecords();
        return Result.ok(records);
    }

    // PostController
    @GetMapping("/of/user")
    public Result queryBlogByUserId(
            @RequestParam(value = "current", defaultValue = "1") @Min(1) Integer current,
            @RequestParam("id") Long id) {
        // 根据用户查询
        Page<Post> page = blogService.query()
                .eq("user_id", id).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Post> records = page.getRecords();
        return Result.ok(records);
    }

    @GetMapping("/hot")
    public Result queryHotBlog(@RequestParam(value = "current", defaultValue = "1") @Min(1) Integer current) {
        return blogService.queryHotBlog(current);
    }

    @GetMapping("/{id}")
    public Result queryBlogById(@PathVariable("id") Long id) {
        return blogService.queryBlogById(id);
    }

    @GetMapping("/likes/{id}")
    public Result queryBlogLikes(@PathVariable("id") Long id) {
        return blogService.queryBlogLikes(id);
    }

    @GetMapping("/of/follow")
    public Result queryBlogOfFollow(
            @RequestParam(value = "lastId", required = false) Long max,
            @RequestParam(value = "offset", defaultValue = "0") Integer offset,
            @RequestParam(value = "cursor", required = false) String cursor) {
        if (cursor != null && !cursor.isBlank()) {
            return blogService.queryBlogOfFollow(cursor);
        }
        if (max == null || max < 0 || offset < 0) {
            throw new com.campusdeal.exception.ValidationException("分页游标不合法");
        }
        return blogService.queryBlogOfFollow(max, offset);
    }
}
