package com.campusdeal.mq;

import com.campusdeal.entity.FlashOrderIntent;
import com.campusdeal.mapper.FlashOrderIntentMapper;
import com.campusdeal.utils.RedisConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FlashReservationRecoverySchedulerTest {

    @Mock StringRedisTemplate redis;
    @Mock FlashOrderIntentMapper intentMapper;
    @Mock HashOperations<String, Object, Object> hashOps;
    @Mock ValueOperations<String, String> valueOps;
    @Mock Cursor<String> cursor;
    @InjectMocks FlashReservationRecoveryScheduler scheduler;

    @Test
    void releasesOnlyExpiredReservationsWithoutDurableIntent() {
        when(redis.scan(any(ScanOptions.class))).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn("flashdeal:reserved:5");
        when(redis.opsForHash()).thenReturn(hashOps);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(hashOps.entries("flashdeal:reserved:5")).thenReturn(Map.of("10", "100"));
        when(redis.hasKey("flashdeal:reservation:100")).thenReturn(false);
        when(intentMapper.selectById(100L)).thenReturn(null);

        scheduler.recover();

        verify(valueOps).increment(RedisConstants.FLASH_DEAL_STOCK_KEY + "5");
        verify(hashOps).delete("flashdeal:reserved:5", "10");
    }

    @Test
    void keepsStockWhenDurableIntentExists() {
        when(redis.scan(any(ScanOptions.class))).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn("flashdeal:reserved:5");
        when(redis.opsForHash()).thenReturn(hashOps);
        when(hashOps.entries("flashdeal:reserved:5")).thenReturn(Map.of("10", "100"));
        when(redis.hasKey("flashdeal:reservation:100")).thenReturn(false);
        when(intentMapper.selectById(100L)).thenReturn(new FlashOrderIntent());

        scheduler.recover();

        verify(hashOps).delete("flashdeal:reserved:5", "10");
        verify(redis, never()).opsForValue();
    }
}
