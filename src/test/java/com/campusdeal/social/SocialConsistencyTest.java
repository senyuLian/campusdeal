package com.campusdeal.social;

import com.campusdeal.dto.UserDTO;
import com.campusdeal.entity.Post;
import com.campusdeal.mapper.FollowMapper;
import com.campusdeal.mapper.PostLikeMapper;
import com.campusdeal.mapper.PostMapper;
import com.campusdeal.security.AuthorizationService;
import com.campusdeal.service.IFollowService;
import com.campusdeal.service.IUserService;
import com.campusdeal.service.impl.FollowServiceImpl;
import com.campusdeal.service.impl.PostServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SocialConsistencyTest {

    @Mock PostMapper postMapper;
    @Mock PostLikeMapper postLikeMapper;
    @Mock AuthorizationService authorizationService;
    @Mock IUserService userService;
    @Mock IFollowService followService;
    @Mock StringRedisTemplate redis;
    @Mock ZSetOperations<String, String> zSetOperations;
    @InjectMocks PostServiceImpl postService;

    @Mock FollowMapper followMapper;
    @Mock SetOperations<String, String> setOperations;
    @InjectMocks FollowServiceImpl followServiceImpl;

    @AfterEach
    void clear() {
        com.campusdeal.utils.UserHolder.removeUser();
    }

    @Test
    void repeatedLikeAndUnlikeOnlyChangeTheCountOnRealRelationTransitions() {
        UserDTO user = user(10L);
        when(authorizationService.requireAuthenticated()).thenReturn(user);
        when(postMapper.selectById(1L)).thenReturn(new Post().setId(1L));
        when(postMapper.update(any(), any())).thenReturn(1);
        when(postLikeMapper.insertIgnore(1L, 10L)).thenReturn(1, 0);
        when(postLikeMapper.deleteRelation(1L, 10L)).thenReturn(1, 0);
        when(redis.opsForZSet()).thenReturn(zSetOperations);
        ReflectionTestUtils.setField(postService, "baseMapper", postMapper);

        postService.likeBlog(1L, true);
        postService.likeBlog(1L, true);
        postService.likeBlog(1L, false);
        postService.likeBlog(1L, false);

        verify(postLikeMapper, times(2)).insertIgnore(1L, 10L);
        verify(postLikeMapper, times(2)).deleteRelation(1L, 10L);
        verify(postMapper, times(2)).update(any(), any());
        verify(zSetOperations).add(eq("post:liked:1"), eq("10"), anyDouble());
        verify(zSetOperations).remove("post:liked:1", "10");
    }

    @Test
    void selfFollowIsRejectedBeforeAnyDatabaseOrRedisMutation() {
        UserDTO user = user(10L);
        when(authorizationService.requireAuthenticated()).thenReturn(user);
        ReflectionTestUtils.setField(followServiceImpl, "baseMapper", followMapper);

        assertThatThrownBy(() -> followServiceImpl.follow(10L, true))
                .isInstanceOf(com.campusdeal.exception.ValidationException.class);

        verify(followMapper, never()).insertIgnore(anyLong(), anyLong());
        verify(setOperations, never()).add(anyString(), anyString());
    }

    @Test
    void repeatedFollowUsesInsertIgnoreAndAnIdempotentRedisMirror() {
        UserDTO user = user(10L);
        when(authorizationService.requireAuthenticated()).thenReturn(user);
        when(followMapper.insertIgnore(10L, 11L)).thenReturn(1, 0);
        when(redis.opsForSet()).thenReturn(setOperations);
        ReflectionTestUtils.setField(followServiceImpl, "baseMapper", followMapper);

        followServiceImpl.follow(11L, true);
        followServiceImpl.follow(11L, true);

        verify(followMapper, times(2)).insertIgnore(10L, 11L);
        verify(setOperations, times(2)).add("follows:10", "11");
    }

    private UserDTO user(long id) {
        UserDTO user = new UserDTO();
        user.setId(id);
        user.setRole("USER");
        return user;
    }
}
