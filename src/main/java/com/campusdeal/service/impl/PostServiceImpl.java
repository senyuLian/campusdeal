package com.campusdeal.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campusdeal.dto.Result;
import com.campusdeal.dto.ScrollResult;
import com.campusdeal.dto.UserDTO;
import com.campusdeal.entity.Post;
import com.campusdeal.entity.Follow;
import com.campusdeal.entity.User;
import com.campusdeal.mapper.PostMapper;
import com.campusdeal.service.IPostService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.campusdeal.service.IFollowService;
import com.campusdeal.service.IUserService;
import com.campusdeal.utils.SystemConstants;
import com.campusdeal.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.campusdeal.utils.RedisConstants.POST_LIKED_KEY;
import static com.campusdeal.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class PostServiceImpl extends ServiceImpl<PostMapper, Post> implements IPostService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;


    @Override
    public Result saveBlog(Post post) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        post.setUserId(user.getId());
        // 保存探店博文
        boolean success = save(post);
        if (success) {
            //推送给粉丝
            List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
            for (Follow follow : follows) {
                Long userId = follow.getUserId();
                String key = FEED_KEY + userId;
                // 发送消息
                stringRedisTemplate.opsForZSet().add(key, post.getId().toString(), System.currentTimeMillis());
            }
        }
        // 返回id
        return Result.ok(post.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //获取用户
        UserDTO user = UserHolder.getUser();
        Long userId = user.getId();
        String key = FEED_KEY + userId;
        //查询redis
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet().
                reverseRangeByScoreWithScores(key, 0, max, offset, 5);

        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }
        List<Long> ids = new ArrayList<>(typedTuples.size());
        int os = 1;
        Long minTime = 0L;

        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            ids.add(Long.valueOf(typedTuple.getValue()));
            if (typedTuple.getScore().longValue() == minTime) {
                os++;
            } else {
                minTime = typedTuple.getScore().longValue();
                os = 1;
            }
        }
        String join = StrUtil.join(",", ids);
        //查询blog
        List<Post> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + join + ")").list();
        for (Post post : blogs) {
            if (post == null) {
                return Result.fail("笔记不存在！");
            }
            // 查询用户
            queryBlogUser(post);
            queryBlogIsLiked(post);
        }
        //封装返回
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setMinTime(minTime);
        scrollResult.setOffset(os);
        return Result.ok(scrollResult);
    }


    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Post> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Post> records = page.getRecords();
        // 查询用户
        records.forEach(post ->{
            this.queryBlogUser(post);
            this.queryBlogIsLiked(post);
        });
        return Result.ok(records);
    }



    @Override
    public Result queryBlogById(Long id) {
        // 1.查询blog
        Post post = getById(id);
        if (post == null) {
            return Result.fail("笔记不存在！");
        }
        // 查询用户
        queryBlogUser(post);
        queryBlogIsLiked(post);
        return Result.ok(post);
    }

    private void queryBlogIsLiked(Post post) {
        // 1.获取登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return;
        }
        Long userId = UserHolder.getUser().getId();
        // 2.判断是否已点赞
        String key = POST_LIKED_KEY + post.getId();
        Double isMember = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        post.setIsLike(isMember != null);
    }

    @Override
    public Result likeBlog(Long id) {
        // 1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.判断是否已点赞
        String key = POST_LIKED_KEY + id;
        Double isMember = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (isMember != null) {
            boolean success = update().setSql("liked = liked - 1").eq("id", id).update();
            // 更新缓存
            if (success) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        else {
            // 3.2.如果未点赞，点赞
            boolean success = update().setSql("liked = liked + 1").eq("id", id).update();
            // 添加缓存
            if (success) {
            stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key = POST_LIKED_KEY + id;
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.EMPTY_LIST);
        }
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String join = StrUtil.join("," ,ids);
        List<UserDTO>  userDTOList =  userService
                .query().in("id", ids).last("ORDER BY FIELD (id,"+ join +")").list()
                .stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());
        return Result.ok(userDTOList);
    }

    private void queryBlogUser(Post post) {
        Long userId = post.getUserId();
        User user = userService.getById(userId);
        post.setName(user.getNickName());
        post.setIcon(user.getIcon());
    }
}
