package com.campusdeal.social;

import com.campusdeal.security.SensitiveLogSanitizer;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.campusdeal.entity.Follow;
import com.campusdeal.entity.PostLike;
import com.campusdeal.mapper.FollowMapper;
import com.campusdeal.mapper.PostLikeMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Repairs MySQL counters and the Redis social mirrors after partial failures. */
@Slf4j
@Component
public class SocialReconciliationScheduler {

    @Resource
    private PostLikeMapper postLikeMapper;
    @Resource
    private FollowMapper followMapper;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Value("${campusdeal.social.reconcile-batch-size:50000}")
    private int batchSize = 50_000;

    @Scheduled(fixedDelayString = "${campusdeal.social.reconcile-interval-ms:300000}")
    public void reconcile() {
        try {
            int counts = postLikeMapper.reconcilePostCounts();
            rebuildLikeMirrors();
            rebuildFollowMirrors();
            log.info("Social reconciliation completed: postCountRows={}", counts);
        } catch (Exception e) {
            // Reconciliation is repair work; a transient database/Redis issue
            // must not stop the scheduler or affect request paths.
            log.warn("Social reconciliation deferred: {}", SensitiveLogSanitizer.exceptionSummary(e));
        }
    }

    private void rebuildLikeMirrors() {
        List<PostLike> rows = postLikeMapper.selectList(new QueryWrapper<PostLike>()
                .select("post_id", "user_id")
                .last("LIMIT " + Math.max(1, Math.min(batchSize, 100_000))));
        Map<Long, List<PostLike>> byPost = rows.stream().collect(Collectors.groupingBy(PostLike::getPostId));
        byPost.forEach((postId, likes) -> {
            String key = "post:liked:" + postId;
            stringRedisTemplate.delete(key);
            likes.forEach(like -> stringRedisTemplate.opsForZSet()
                    .add(key, like.getUserId().toString(), System.currentTimeMillis()));
        });
    }

    private void rebuildFollowMirrors() {
        List<Follow> rows = followMapper.selectList(new QueryWrapper<Follow>()
                .select("user_id", "follow_user_id")
                .last("LIMIT " + Math.max(1, Math.min(batchSize, 100_000))));
        Map<Long, List<Follow>> byUser = rows.stream().collect(Collectors.groupingBy(Follow::getUserId));
        byUser.forEach((userId, follows) -> {
            String key = "follows:" + userId;
            stringRedisTemplate.delete(key);
            follows.forEach(follow -> stringRedisTemplate.opsForSet()
                    .add(key, follow.getFollowUserId().toString()));
        });
    }
}
