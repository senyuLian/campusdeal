package com.campusdeal.service;

import com.campusdeal.entity.FlashDeal;
import com.campusdeal.entity.FlashOrderIntent;
import com.campusdeal.mapper.CouponOrderMapper;
import com.campusdeal.mapper.FlashDealMapper;
import com.campusdeal.mapper.FlashOrderIntentMapper;
import com.campusdeal.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;

@ExtendWith(MockitoExtension.class)
class FlashOrderIntentServiceTest {

    @Mock FlashDealMapper flashDealMapper;
    @Mock FlashOrderIntentMapper intentMapper;
    @Mock CouponOrderMapper couponOrderMapper;
    @Mock RedisIdWorker redisIdWorker;
    @Mock OutboxService outboxService;
    @Mock StringRedisTemplate stringRedisTemplate;
    @Mock ValueOperations<String, String> valueOperations;
    @Mock SetOperations<String, String> setOperations;
    @Mock DefaultRedisScript<Long> reservationScript;
    @Mock DefaultRedisScript<Long> releaseScript;
    @Mock DefaultRedisScript<Long> commitScript;
    @InjectMocks FlashOrderIntentService service;

    @org.junit.jupiter.api.BeforeEach
    void stubRedisOperations() {
        org.mockito.Mockito.lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        org.mockito.Mockito.lenient().when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
    }

    @Test
    void acceptanceWritesStockIntentAndOutboxBeforeReturningOrder() {
        FlashDeal deal = new FlashDeal().setVoucherId(5L).setStock(2)
                .setBeginTime(LocalDateTime.now().minusMinutes(1))
                .setEndTime(LocalDateTime.now().plusMinutes(5));
        when(flashDealMapper.selectById(5L)).thenReturn(deal);
        when(redisIdWorker.getNextId("order")).thenReturn(9001L);
        when(flashDealMapper.decrementStock(5L)).thenReturn(1);
        when(intentMapper.insertIgnoreIntent(any(FlashOrderIntent.class))).thenReturn(1);

        var result = service.accept(5L, 10L);

        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getData()).isEqualTo("9001");
        verify(flashDealMapper).decrementStock(5L);
        verify(intentMapper).insertIgnoreIntent(any(FlashOrderIntent.class));
        verify(outboxService).record(anyString(), anyString(), any());
    }

    @Test
    void duplicateIntentReturnsExistingOrderWithoutConsumingStock() {
        FlashOrderIntent existing = new FlashOrderIntent();
        existing.setOrderId(7001L);
        existing.setUserId(10L);
        existing.setVoucherId(5L);
        when(intentMapper.selectOne(any())).thenReturn(existing);

        var result = service.accept(5L, 10L);

        assertThat(result.getData()).isEqualTo("7001");
        verify(flashDealMapper, never()).decrementStock(anyLong());
        verify(outboxService, never()).record(anyString(), anyString(), any());
    }

    @Test
    void redisReservationIsCommittedOnlyAfterDurableIntent() {
        FlashDeal deal = new FlashDeal().setVoucherId(5L).setStock(2)
                .setBeginTime(LocalDateTime.now().minusMinutes(1))
                .setEndTime(LocalDateTime.now().plusMinutes(5));
        when(flashDealMapper.selectById(5L)).thenReturn(deal);
        when(redisIdWorker.getNextId("order")).thenReturn(9002L);
        when(stringRedisTemplate.execute(eq(reservationScript), anyList(), any(), any(), any()))
                .thenReturn(1L);
        when(flashDealMapper.decrementStock(5L)).thenReturn(1);
        when(intentMapper.insertIgnoreIntent(any(FlashOrderIntent.class))).thenReturn(1);

        var result = service.accept(5L, 10L);

        assertThat(result.getData()).isEqualTo("9002");
        verify(stringRedisTemplate).execute(eq(commitScript), anyList(), any());
    }

    @Test
    void redisReservationRejectsSoldOutBeforeDatabaseMutation() {
        FlashDeal deal = new FlashDeal().setVoucherId(5L).setStock(1)
                .setBeginTime(LocalDateTime.now().minusMinutes(1))
                .setEndTime(LocalDateTime.now().plusMinutes(5));
        when(flashDealMapper.selectById(5L)).thenReturn(deal);
        when(redisIdWorker.getNextId("order")).thenReturn(9003L);
        when(stringRedisTemplate.execute(eq(reservationScript), anyList(), any(), any(), any()))
                .thenReturn(-1L);

        var result = service.accept(5L, 10L);

        assertThat(result.getSuccess()).isFalse();
        verify(flashDealMapper, never()).decrementStock(5L);
        verify(outboxService, never()).record(anyString(), anyString(), any());
    }

    @Test
    void shadowComparisonReportsLegacyRedisDriftWithoutMutatingState() {
        FlashDeal deal = new FlashDeal().setVoucherId(5L).setStock(2)
                .setBeginTime(LocalDateTime.now().minusMinutes(1))
                .setEndTime(LocalDateTime.now().plusMinutes(5));
        when(flashDealMapper.selectById(5L)).thenReturn(deal);
        when(valueOperations.get("flashdeal:stock:5")).thenReturn("0");
        when(setOperations.isMember("flashdeal:order:5", "10")).thenReturn(false);

        var comparison = service.compareLegacyAdmission(5L, 10L);

        assertThat(comparison.legacyDecision()).isEqualTo("SOLD_OUT");
        assertThat(comparison.durableDecision()).isEqualTo("AVAILABLE");
        assertThat(comparison.discrepant()).isTrue();
        verify(valueOperations, never()).set(anyString(), anyString());
    }
}
