package com.campusdeal.social;

import com.campusdeal.entity.Follow;
import com.campusdeal.entity.PostLike;
import com.campusdeal.mapper.FollowMapper;
import com.campusdeal.mapper.PostLikeMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SocialReconciliationSchedulerTest {

    @Mock PostLikeMapper postLikeMapper;
    @Mock FollowMapper followMapper;
    @Mock StringRedisTemplate redis;
    @Mock ZSetOperations<String, String> zsets;
    @Mock SetOperations<String, String> sets;
    @InjectMocks SocialReconciliationScheduler scheduler;

    @Test
    void rebuildsCountersAndBothRedisMirrorsFromDurableRelations() {
        when(postLikeMapper.reconcilePostCounts()).thenReturn(2);
        PostLike like = new PostLike();
        like.setPostId(5L);
        like.setUserId(10L);
        when(postLikeMapper.selectList(any())).thenReturn(List.of(like));
        when(followMapper.selectList(any())).thenReturn(List.of(
                new Follow().setUserId(10L).setFollowUserId(11L)));
        when(redis.opsForZSet()).thenReturn(zsets);
        when(redis.opsForSet()).thenReturn(sets);

        scheduler.reconcile();

        verify(postLikeMapper).reconcilePostCounts();
        verify(redis).delete("post:liked:5");
        verify(zsets).add(eq("post:liked:5"), eq("10"), anyDouble());
        verify(redis).delete("follows:10");
        verify(sets).add("follows:10", "11");
    }
}
