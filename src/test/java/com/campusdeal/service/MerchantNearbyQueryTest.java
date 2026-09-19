package com.campusdeal.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.SharedString;
import com.campusdeal.entity.Merchant;
import com.campusdeal.mapper.MerchantMapper;
import com.campusdeal.security.AuthorizationService;
import com.campusdeal.service.impl.MerchantServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.geo.Circle;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MerchantNearbyQueryTest {

    @Mock MerchantMapper merchantMapper;
    @Mock AuthorizationService authorizationService;
    @Mock StringRedisTemplate redis;
    @Mock GeoOperations<String, String> geoOperations;
    @InjectMocks MerchantServiceImpl service;

    @Test
    void geoMissFallsBackToBoundedCoordinateQuery() {
        ReflectionTestUtils.setField(service, "baseMapper", merchantMapper);
        when(redis.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.radius(any(String.class), any(Circle.class),
                any(RedisGeoCommands.GeoRadiusCommandArgs.class))).thenReturn(null);
        Merchant near = new Merchant().setId(1L).setX(120.0).setY(30.0);
        when(merchantMapper.selectList(any())).thenReturn(List.of(near));

        var result = service.queryNearby(120.0, 30.0, 1);

        assertThat((List<?>) result.getData()).hasSize(1);
        ArgumentCaptor<QueryWrapper<Merchant>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(merchantMapper).selectList(captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("y");
        Object lastSql = ReflectionTestUtils.getField(captor.getValue(), "lastSql");
        assertThat(((SharedString) lastSql).getStringValue()).contains("LIMIT 40");
    }
}
