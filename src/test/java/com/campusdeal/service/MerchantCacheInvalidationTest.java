package com.campusdeal.service;

import com.campusdeal.entity.Merchant;
import com.campusdeal.mapper.MerchantMapper;
import com.campusdeal.security.AuthorizationService;
import com.campusdeal.service.impl.MerchantServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MerchantCacheInvalidationTest {

    @Mock MerchantMapper merchantMapper;
    @Mock AuthorizationService authorizationService;
    @Mock StringRedisTemplate redis;
    @InjectMocks MerchantServiceImpl service;

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void rollbackDoesNotEvictExistingCache() {
        ReflectionTestUtils.setField(service, "baseMapper", merchantMapper);
        when(merchantMapper.selectById(7L)).thenReturn(new Merchant().setId(7L).setTypeId(2L));
        when(merchantMapper.updateById(any())).thenReturn(1);
        TransactionSynchronizationManager.initSynchronization();

        service.updateShop(new Merchant().setId(7L).setTypeId(2L).setName("new"));

        verify(redis, never()).delete(any(String.class));
    }

    @Test
    void commitRunsInvalidationOnlyAfterDatabaseMutation() {
        ReflectionTestUtils.setField(service, "baseMapper", merchantMapper);
        when(merchantMapper.selectById(7L)).thenReturn(new Merchant().setId(7L).setTypeId(2L));
        when(merchantMapper.updateById(any())).thenReturn(1);
        TransactionSynchronizationManager.initSynchronization();

        service.updateShop(new Merchant().setId(7L).setTypeId(2L).setName("new"));
        verify(redis, never()).delete(any(String.class));
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }

        verify(redis).delete("cache:merchant:7");
    }
}
