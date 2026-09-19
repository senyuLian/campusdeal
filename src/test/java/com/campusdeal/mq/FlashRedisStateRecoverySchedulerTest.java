package com.campusdeal.mq;

import com.campusdeal.entity.CouponOrder;
import com.campusdeal.entity.FlashDeal;
import com.campusdeal.entity.FlashOrderIntent;
import com.campusdeal.mapper.CouponOrderMapper;
import com.campusdeal.mapper.FlashDealMapper;
import com.campusdeal.mapper.FlashOrderIntentMapper;
import com.campusdeal.utils.RedisConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FlashRedisStateRecoverySchedulerTest {

    @Mock FlashDealMapper dealMapper;
    @Mock CouponOrderMapper orderMapper;
    @Mock FlashOrderIntentMapper intentMapper;
    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> values;
    @Mock SetOperations<String, String> sets;
    @InjectMocks FlashRedisStateRecoveryScheduler scheduler;

    @Test
    void rebuildsMissingStockAndPurchaserMirrorsFromDurableRows() {
        FlashDeal deal = new FlashDeal().setVoucherId(5L).setStock(3)
                .setEndTime(LocalDateTime.now().plusMinutes(10));
        when(dealMapper.selectList(any())).thenReturn(List.of(deal));
        when(redis.hasKey(RedisConstants.FLASH_DEAL_STOCK_KEY + "5")).thenReturn(false);
        when(redis.hasKey(RedisConstants.FLASH_DEAL_ORDER_KEY + "5")).thenReturn(false);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForSet()).thenReturn(sets);
        CouponOrder order = new CouponOrder().setUserId(10L);
        FlashOrderIntent intent = new FlashOrderIntent();
        intent.setUserId(11L);
        when(orderMapper.selectList(any())).thenReturn(List.of(order));
        when(intentMapper.selectList(any())).thenReturn(List.of(intent));

        scheduler.recover();

        verify(values).setIfAbsent(RedisConstants.FLASH_DEAL_STOCK_KEY + "5", "3");
        ArgumentCaptor<String[]> users = ArgumentCaptor.forClass(String[].class);
        verify(sets).add(eq(RedisConstants.FLASH_DEAL_ORDER_KEY + "5"), users.capture());
        org.assertj.core.api.Assertions.assertThat(users.getValue()).containsExactlyInAnyOrder("10", "11");
    }
}
