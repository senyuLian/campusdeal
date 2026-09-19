package com.campusdeal.social;

import com.campusdeal.dto.UserDTO;
import com.campusdeal.entity.Post;
import com.campusdeal.entity.PostLike;
import com.campusdeal.entity.User;
import com.campusdeal.mapper.PostLikeMapper;
import com.campusdeal.mapper.PostMapper;
import com.campusdeal.security.AuthorizationService;
import com.campusdeal.service.IFollowService;
import com.campusdeal.service.IUserService;
import com.campusdeal.service.impl.PostServiceImpl;
import com.campusdeal.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.ArrayList;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostEnrichmentTest {

    @Mock PostMapper postMapper;
    @Mock PostLikeMapper postLikeMapper;
    @Mock IUserService userService;
    @Mock IFollowService followService;
    @Mock AuthorizationService authorizationService;
    @Mock StringRedisTemplate redis;
    @InjectMocks PostServiceImpl service;

    @AfterEach
    void clear() {
        UserHolder.removeUser();
    }

    @Test
    void enrichmentBatchesAuthorsAndViewerLikes() {
        UserDTO viewer = new UserDTO();
        viewer.setId(99L);
        UserHolder.saveUser(viewer);
        Post first = new Post().setId(1L).setUserId(10L);
        Post second = new Post().setId(2L).setUserId(11L);
        User author1 = new User().setId(10L).setNickName("a");
        User author2 = new User().setId(11L).setNickName("b");
        PostLike like = new PostLike();
        like.setPostId(2L);
        when(userService.listByIds(anyList())).thenReturn(List.of(author1, author2));
        when(postLikeMapper.selectList(any())).thenReturn(List.of(like));
        ReflectionTestUtils.invokeMethod(service, "enrichBlogs", List.of(first, second));

        verify(userService).listByIds(anyList());
        verify(postLikeMapper).selectList(any());
        org.assertj.core.api.Assertions.assertThat(first.getName()).isEqualTo("a");
        org.assertj.core.api.Assertions.assertThat(second.getIsLike()).isTrue();
    }

    @Test
    void enrichmentQueryCountStaysConstantAsPageGrows() {
        UserDTO viewer = new UserDTO();
        viewer.setId(99L);
        UserHolder.saveUser(viewer);
        List<Post> posts = new ArrayList<>();
        List<User> authors = new ArrayList<>();
        for (long i = 1; i <= 50; i++) {
            posts.add(new Post().setId(i).setUserId(i));
            authors.add(new User().setId(i).setNickName("u" + i));
        }
        when(userService.listByIds(anyList())).thenReturn(authors);
        when(postLikeMapper.selectList(any())).thenReturn(List.of());

        ReflectionTestUtils.invokeMethod(service, "enrichBlogs", posts);

        verify(userService, times(1)).listByIds(anyList());
        verify(postLikeMapper, times(1)).selectList(any());
    }
}
