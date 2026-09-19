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
import com.campusdeal.mapper.PostLikeMapper;
import com.campusdeal.entity.PostLike;
import com.campusdeal.service.IPostService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.campusdeal.service.IFollowService;
import com.campusdeal.service.IUserService;
import com.campusdeal.utils.SystemConstants;
import com.campusdeal.utils.UserHolder;
import com.campusdeal.security.AuthorizationService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import jakarta.annotation.Resource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.campusdeal.utils.RedisConstants.POST_LIKED_KEY;
import static com.campusdeal.utils.RedisConstants.FEED_KEY;
import com.campusdeal.social.FeedCursorCodec;

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

    @Resource
    private AuthorizationService authorizationService;

    @Resource
    private PostLikeMapper postLikeMapper;


    @Override
    @Transactional
    public Result saveBlog(Post post) {
        // 获取登录用户
        UserDTO user = authorizationService.requireAuthenticated();
        if (post == null || StrUtil.isBlank(post.getTitle()) || StrUtil.isBlank(post.getContent())) {
            throw new com.campusdeal.exception.ValidationException("标题和内容不能为空");
        }
        post.setUserId(user.getId());
        // 保存探店博文
        boolean success = save(post);
        if (success) {
            //推送给粉丝
            List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
            Runnable publish = () -> {
                for (Follow follow : follows) {
                    Long followerId = follow.getUserId();
                    String key = FEED_KEY + followerId;
                    stringRedisTemplate.opsForZSet().add(key, post.getId().toString(), System.currentTimeMillis());
                }
            };
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        publish.run();
                    }
                });
            } else {
                publish.run();
            }
        }
        // 返回id
        return Result.ok(post.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //获取用户
        UserDTO user = authorizationService.requireAuthenticated();
        Long userId = user.getId();
        String key = FEED_KEY + userId;
        //查询redis
        if (max == null || max < 0 || offset == null || offset < 0) {
            throw new com.campusdeal.exception.ValidationException("分页游标不合法");
        }
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet().
                reverseRangeByScoreWithScores(key, 0, max, offset, 5);

        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }
        List<Long> ids = new ArrayList<>(typedTuples.size());
        int trailingEqual = 0;
        Long minTime = null;

        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            Long id = parseFeedMember(typedTuple.getValue());
            if (id == null || typedTuple.getScore() == null) {
                continue;
            }
            ids.add(id);
            long score = typedTuple.getScore().longValue();
            if (minTime == null || score != minTime) {
                minTime = score;
                trailingEqual = 1;
            } else {
                trailingEqual++;
            }
        }
        if (ids.isEmpty()) {
            return Result.ok();
        }
        String join = StrUtil.join(",", ids);
        //查询blog
        List<Post> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + join + ")").list();
        enrichBlogs(blogs);
        //封装返回
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setMinTime(minTime);
        // Redis' offset is relative to the requested upper score. Carry the
        // previous offset forward when the page ends on that same score.
        int nextOffset = minTime != null && minTime == max ? offset + trailingEqual : trailingEqual;
        scrollResult.setOffset(nextOffset);
        if (minTime != null && !ids.isEmpty()) {
            String lastMember = String.valueOf(ids.get(ids.size() - 1));
            scrollResult.setSnapshotMaxTime(max);
            scrollResult.setCursor(FeedCursorCodec.encode(max, minTime, lastMember));
        }
        return Result.ok(scrollResult);
    }

    @Override
    public Result queryBlogOfFollow(String cursor) {
        UserDTO user = authorizationService.requireAuthenticated();
        FeedCursorCodec.Cursor decoded = FeedCursorCodec.decode(cursor);
        String key = FEED_KEY + user.getId();
        // Keep the snapshot upper bound fixed. Entries with the same score are
        // ordered by their deterministic Redis member and resumed after the
        // last member, so equal timestamps cannot repeat or disappear.
        Set<ZSetOperations.TypedTuple<String>> tuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, decoded.snapshotMaxTime(), 0, 1000);
        if (tuples == null || tuples.isEmpty()) {
            return Result.ok();
        }
        List<ZSetOperations.TypedTuple<String>> page = tuples.stream()
                .filter(tuple -> tuple.getValue() != null && tuple.getScore() != null)
                .filter(tuple -> parseFeedMember(tuple.getValue()) != null)
                .filter(tuple -> {
                    long score = tuple.getScore().longValue();
                    if (score < decoded.score()) return true;
                    if (score > decoded.score()) return false;
                    return tuple.getValue().compareTo(decoded.member()) < 0;
                })
                .limit(5)
                .toList();
        if (page.isEmpty()) return Result.ok();
        List<Long> ids = page.stream().map(t -> parseFeedMember(t.getValue())).toList();
        String join = StrUtil.join(",", ids);
        List<Post> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + join + ")").list();
        enrichBlogs(blogs);
        ZSetOperations.TypedTuple<String> last = page.get(page.size() - 1);
        long lastScore = last.getScore().longValue();
        String next = FeedCursorCodec.encode(decoded.snapshotMaxTime(), lastScore, last.getValue());
        ScrollResult result = new ScrollResult();
        result.setList(blogs);
        result.setMinTime(lastScore);
        result.setSnapshotMaxTime(decoded.snapshotMaxTime());
        result.setCursor(next);
        result.setOffset(0);
        return Result.ok(result);
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
        enrichBlogs(records);
        return Result.ok(records);
    }



    @Override
    public Result queryBlogById(Long id) {
        // 1.查询blog
        Post post = getById(id);
        if (post == null) {
            return Result.fail("笔记不存在！");
        }
        enrichBlogs(List.of(post));
        return Result.ok(post);
    }

    @Override
    @Transactional
    public Result likeBlog(Long id) {
        // Keep the legacy endpoint deterministic: a PUT means the caller
        // wants the liked state. Explicit unlike calls use the overload below.
        return likeBlog(id, true);
    }

    @Override
    @Transactional
    public Result likeBlog(Long id, Boolean isLike) {
        Long userId = authorizationService.requireAuthenticated().getId();
        if (id == null || getById(id) == null) {
            throw new com.campusdeal.exception.ValidationException("笔记不存在");
        }
        String key = POST_LIKED_KEY + id;
        if (isLike == null) {
            throw new com.campusdeal.exception.ValidationException("点赞状态不能为空");
        }
        if (isLike) {
            int inserted = postLikeMapper.insertIgnore(id, userId);
            if (inserted == 1) {
                update().setSql("liked = COALESCE(liked, 0) + 1").eq("id", id).update();
                registerLikeMirror(() -> stringRedisTemplate.opsForZSet()
                        .add(key, userId.toString(), System.currentTimeMillis()));
            }
        } else {
            int removed = postLikeMapper.deleteRelation(id, userId);
            if (removed == 1) {
                update().setSql("liked = GREATEST(COALESCE(liked, 0) - 1, 0)").eq("id", id).update();
                registerLikeMirror(() -> stringRedisTemplate.opsForZSet().remove(key, userId.toString()));
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key = POST_LIKED_KEY + id;
        List<PostLike> relations = postLikeMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PostLike>()
                .eq(PostLike::getPostId, id).orderByDesc(PostLike::getCreateTime).last("LIMIT 5"));
        if (relations == null || relations.isEmpty()) {
            return Result.ok(Collections.EMPTY_LIST);
        }
        List<Long> ids = relations.stream().map(PostLike::getUserId).collect(Collectors.toList());
        String join = StrUtil.join("," ,ids);
        List<UserDTO>  userDTOList =  userService
                .query().in("id", ids).last("ORDER BY FIELD (id,"+ join +")").list()
                .stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());
        return Result.ok(userDTOList);
    }

    private void enrichBlogs(List<Post> posts) {
        if (posts == null || posts.isEmpty()) return;
        List<Long> userIds = posts.stream().map(Post::getUserId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        java.util.Map<Long, User> users = userIds.isEmpty() ? java.util.Map.of()
                : userService.listByIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));
        for (Post post : posts) {
            User user = users.get(post.getUserId());
            if (user != null) {
                post.setName(user.getNickName());
                post.setIcon(user.getIcon());
            }
        }
        UserDTO viewer = UserHolder.getUser();
        if (viewer == null || viewer.getId() == null) return;
        List<Long> postIds = posts.stream().map(Post::getId)
                .filter(java.util.Objects::nonNull).toList();
        if (postIds.isEmpty()) return;
        Set<Long> liked = postLikeMapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<PostLike>()
                                .in("post_id", postIds)
                                .eq("user_id", viewer.getId()))
                .stream().map(PostLike::getPostId).collect(Collectors.toSet());
        posts.forEach(post -> post.setIsLike(liked.contains(post.getId())));
    }

    private void registerLikeMirror(Runnable mirror) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    mirror.run();
                }
            });
        } else {
            mirror.run();
        }
    }

    /** Redis feed members are external state; reject malformed IDs before
     * using them in the bounded FIELD ordering clause. */
    private Long parseFeedMember(String member) {
        if (member == null || !member.matches("\\d+")) {
            return null;
        }
        try {
            return Long.valueOf(member);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
