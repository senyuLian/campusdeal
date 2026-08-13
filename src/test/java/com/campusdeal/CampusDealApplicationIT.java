package com.campusdeal;

import com.campusdeal.entity.Merchant;
import com.campusdeal.service.IMerchantService;
import com.campusdeal.utils.RedisConstants;
import com.campusdeal.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import jakarta.annotation.Resource;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@SpringBootTest
class CampusDealApplicationIT {

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IMerchantService merchantService;

    private ExecutorService es = Executors.newFixedThreadPool(500);

    @Test
    public void testRedisWorker() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);
        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.getNextId("order");
                System.out.println("id = " + id);
            }
            latch.countDown();
        };
        long start = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("time: " + (end - start));

        es.shutdown();
        try {
            if (!es.awaitTermination(60, TimeUnit.SECONDS)) {
                es.shutdownNow();
            }
        } catch (InterruptedException e) {
            es.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Test
    public void loadMerchantData() {
        List<Merchant> list = merchantService.list();
        Map<Long, List<Merchant>> map = list.stream().collect(Collectors.groupingBy(Merchant::getTypeId));
        for (Map.Entry<Long, List<Merchant>> entry : map.entrySet()) {
            Long typeId = entry.getKey();
            List<Merchant> value = entry.getValue();
            String key = RedisConstants.MERCHANT_GEO_KEY + typeId;
            value.forEach(merchant -> {
                stringRedisTemplate.opsForGeo().add(key, new Point(merchant.getX(), merchant.getY()), merchant.getId().toString());
            });
        }
    }
}
