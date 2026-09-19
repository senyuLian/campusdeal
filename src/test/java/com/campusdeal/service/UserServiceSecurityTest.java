package com.campusdeal.service;

import com.campusdeal.dto.LoginFormDTO;
import com.campusdeal.dto.Result;
import com.campusdeal.exception.RateLimitException;
import com.campusdeal.mapper.UserMapper;
import com.campusdeal.security.SecurityProperties;
import com.campusdeal.service.impl.UserServiceImpl;
import com.campusdeal.utils.RedisConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceSecurityTest {

    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> values;
    @Mock HashOperations<String, Object, Object> hashes;
    @Mock UserMapper userMapper;
    @Mock SecurityProperties securityProperties;
    @InjectMocks UserServiceImpl service;

    @Test
    void sendCodeUsesPhoneAndOriginRateWindowsAndStoresTtlBoundedCode() {
        when(redis.opsForValue()).thenReturn(values);
        when(redis.execute(any(), anyList(), anyString())).thenReturn(1L);
        when(securityProperties.getVerificationSendPerMinute()).thenReturn(3);

        var result = service.sendCode("13812345678", "127.0.0.1");

        assertThat(result.getSuccess()).isTrue();
        verify(values).set(eq(RedisConstants.LOGIN_CODE_KEY + "13812345678"), anyString(),
                eq(RedisConstants.LOGIN_CODE_TTL), eq(TimeUnit.MINUTES));
    }

    @Test
    void sendCodeIsThrottledBeforeGeneratingOrStoringASecondCode() {
        when(redis.execute(any(), anyList(), anyString())).thenReturn(4L);
        when(securityProperties.getVerificationSendPerMinute()).thenReturn(3);

        assertThatThrownBy(() -> service.sendCode("13812345678", "127.0.0.1"))
                .isInstanceOf(RateLimitException.class);
        verify(redis, never()).opsForValue();
    }

    @Test
    void loginUsesAtomicCompareAndDeleteAndDoesNotPersistOnConsumedCode() {
        when(redis.execute(any(), anyList(), anyString())).thenReturn(1L, 0L);
        when(securityProperties.getVerificationAttemptPerMinute()).thenReturn(10);
        LoginFormDTO form = new LoginFormDTO();
        form.setPhone("13812345678");
        form.setCode("123456");

        var result = service.login(form, "127.0.0.1");

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("INVALID_CODE");
        verify(userMapper, never()).insert(any());
    }

    @Test
    void concurrentReuseOfOneCodeProducesOnlyOneSuccessfulLogin() throws Exception {
        when(redis.opsForHash()).thenReturn(hashes);
        when(securityProperties.getVerificationAttemptPerMinute()).thenReturn(10);
        ReflectionTestUtils.setField(service, "baseMapper", userMapper);
        var existing = new com.campusdeal.entity.User().setId(7L).setPhone("13812345678")
                .setPassword("").setNickName("tester").setIcon("").setRole("USER")
                .setCreateTime(LocalDateTime.now()).setUpdateTime(LocalDateTime.now());
        when(userMapper.selectOne(any())).thenReturn(existing);
        when(redis.expire(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);

        AtomicBoolean consumed = new AtomicBoolean();
        when(redis.execute(any(), anyList(), anyString())).thenAnswer(invocation -> {
            DefaultRedisScript<?> script = invocation.getArgument(0);
            if (script.getScriptAsString().contains("INCR")) {
                return 1L;
            }
            return consumed.compareAndSet(false, true) ? 1L : 0L;
        });

        LoginFormDTO form = new LoginFormDTO();
        form.setPhone("13812345678");
        form.setCode("123456");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<Result> first = executor.submit(() -> {
                start.await();
                return service.login(form, "127.0.0.1");
            });
            Future<Result> second = executor.submit(() -> {
                start.await();
                return service.login(form, "127.0.0.1");
            });
            start.countDown();
            Result firstResult = first.get(3, TimeUnit.SECONDS);
            Result secondResult = second.get(3, TimeUnit.SECONDS);
            assertThat(List.of(firstResult, secondResult).stream().filter(Result::getSuccess).count()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }
}
