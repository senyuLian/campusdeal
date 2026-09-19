package com.campusdeal.social;

import com.campusdeal.dto.Result;
import com.campusdeal.dto.ScrollResult;
import com.campusdeal.dto.UserDTO;
import com.campusdeal.entity.Post;
import com.campusdeal.mapper.PostLikeMapper;
import com.campusdeal.mapper.PostMapper;
import com.campusdeal.security.AuthorizationService;
import com.campusdeal.service.IUserService;
import com.campusdeal.service.IFollowService;
import com.campusdeal.service.impl.PostServiceImpl;
import com.campusdeal.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeedPaginationTest {

    @Mock PostMapper postMapper;
    @Mock PostLikeMapper postLikeMapper;
    @Mock IUserService userService;
    @Mock IFollowService followService;
    @Mock AuthorizationService authorizationService;
    @Mock StringRedisTemplate redis;
    @Mock ZSetOperations<String, String> zsets;
    @InjectMocks PostServiceImpl service;

    @AfterEach
    void clearPrincipal() {
        UserHolder.removeUser();
    }

    @Test
    void cursorKeepsEqualScoreBoundaryAndExcludesPostsInsertedAfterSnapshot() {
        UserDTO viewer = new UserDTO();
        viewer.setId(1L);
        UserHolder.saveUser(viewer);
        when(authorizationService.requireAuthenticated()).thenReturn(viewer);
        when(redis.opsForZSet()).thenReturn(zsets);
        when(userService.listByIds(any())).thenReturn(List.of());
        when(postLikeMapper.selectList(any())).thenReturn(List.of());
        ReflectionTestUtils.setField(service, "baseMapper", postMapper);

        Set<ZSetOperations.TypedTuple<String>> first = tuples(7, 6, 5, 4, 3);
        Set<ZSetOperations.TypedTuple<String>> second = tuples(8, 7, 6, 5, 4, 3, 2, 1);
        when(zsets.reverseRangeByScoreWithScores(any(String.class), any(double.class), any(double.class),
                any(long.class), any(long.class))).thenReturn(first, second);

        when(postMapper.selectList(any())).thenReturn(posts(7, 6, 5, 4, 3), posts(2, 1));

        Result firstResult = service.queryBlogOfFollow(1000L, 0);
        String cursor = ((ScrollResult) firstResult.getData()).getCursor();
        Result secondResult = service.queryBlogOfFollow(cursor);

        List<Long> firstIds = ((List<?>) ((ScrollResult) firstResult.getData()).getList()).stream()
                .map(post -> ((Post) post).getId()).toList();
        List<Long> secondIds = ((List<?>) ((ScrollResult) secondResult.getData()).getList()).stream()
                .map(post -> ((Post) post).getId()).toList();
        assertThat(firstIds).containsExactly(7L, 6L, 5L, 4L, 3L);
        assertThat(secondIds).containsExactly(2L, 1L);
        assertThat(secondIds).doesNotContain(8L);
        assertThat(new java.util.HashSet<>(firstIds)).doesNotContainAnyElementsOf(secondIds);
    }

    private Set<ZSetOperations.TypedTuple<String>> tuples(long... ids) {
        Set<ZSetOperations.TypedTuple<String>> result = new LinkedHashSet<>();
        for (long id : ids) {
            result.add(new DefaultTypedTuple<>(String.valueOf(id), 1000.0));
        }
        return result;
    }

    private List<Post> posts(long... ids) {
        List<Post> result = new ArrayList<>();
        for (long id : ids) {
            result.add(new Post().setId(id).setUserId(10L));
        }
        return result;
    }
}
