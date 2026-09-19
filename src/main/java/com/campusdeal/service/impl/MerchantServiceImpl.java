package com.campusdeal.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campusdeal.dto.Result;
import com.campusdeal.entity.Merchant;
import com.campusdeal.mapper.MerchantMapper;
import com.campusdeal.service.IMerchantService;
import com.campusdeal.security.AuthorizationService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.campusdeal.utils.CacheClient;
import com.campusdeal.utils.RedisData;
import com.campusdeal.utils.SystemConstants;
import io.netty.util.internal.StringUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import jakarta.annotation.Resource;
import org.springframework.util.CollectionUtils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static com.campusdeal.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class MerchantServiceImpl extends ServiceImpl<MerchantMapper, Merchant> implements IMerchantService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private AuthorizationService authorizationService;

    /**
     * Merchant creation is a protected operation.  The owner is derived from
     * the token instead of trusting a client supplied owner_user_id.
     */
    @Override
    @Transactional
    public boolean save(Merchant merchant) {
        var user = authorizationService.requireAuthenticated();
        if (merchant == null) {
            return false;
        }
        boolean updating = merchant.getId() != null;
        if (updating) {
            authorizationService.requireMerchantOwner(merchant.getId());
        }
        if (updating) {
            merchant.setOwnerUserId(null);
        } else if (!authorizationService.isAdmin(user) || merchant.getOwnerUserId() == null) {
            merchant.setOwnerUserId(user.getId());
        }
        boolean saved = super.save(merchant);
        if (saved) {
            registerCacheInvalidation(merchant.getId(), null, merchant.getTypeId(), merchant);
        }
        return saved;
    }



    @Override
    public Result queryById(Long id) {
        //缓存穿透
//        Merchant merchant = queryWithPassThrough(id);
        //互斥锁缓存击穿
//        Merchant merchant = queryWithMutex(id);
        //逻辑过期缓存击穿
//        Merchant merchant = queryWithLogicalExpire(id);
        Merchant merchant = cacheClient.queryWithLogicalExpire(id, CACHE_MERCHANT_KEY, Merchant.class, id1 -> getById(id1), CACHE_MERCHANT_TTL, TimeUnit.MINUTES);
        //Merchant merchant = cacheClient.queryWithPassThrough(id, CACHE_MERCHANT_KEY, Merchant.class, id1 -> getById(id1), CACHE_MERCHANT_TTL, TimeUnit.MINUTES);
        if (merchant == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(merchant);
    }

//    private Merchant queryWithLogicalExpire(Long id) {
//        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_MERCHANT_KEY + id);
//        if (StringUtils.isBlank(shopJson)) {
//            return null;
//        }
//        //命中需要判断是否过期
//        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
//        JSONObject jsonShop = (JSONObject)redisData.getData();
//        Merchant merchant = JSONUtil.toBean(jsonShop, Merchant.class);
//        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
//            return merchant;
//        }
//        //已经过期，获取锁，重新写入
//        String lock = LOCK_MERCHANT_KEY + id;
//        if (tryLock(lock)) {
//            //开启独立线程进行缓存重建
//            CACHE_REBUILDER_EXECUTOR.submit(() -> {
//                try {
//                    this.saveShop2Redis(id, 20L);
//                } catch (Exception e) {
//                    throw new RuntimeException(e);
//                } finally {
//                    unLock(lock);
//                }
//            });
//        }
//
//        return merchant;
//    }


    /**
     * 缓存击穿：互斥锁
     * @param merchant
     * @return
     */
//    private Merchant queryWithMutex(Long id) {
//        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_MERCHANT_KEY + id);
//        if (StringUtils.isNotBlank(shopJson)) {
//            Merchant merchant = BeanUtil.toBean(shopJson, Merchant.class);
//            return merchant;
//        }
//        if (shopJson != null) {
//            return null;
//        }
//        //尝试获取互斥锁
//        String lock = LOCK_MERCHANT_KEY + id;
//        boolean flag = tryLock(lock);
//        if (!flag) {
//            //获取锁失败，则休眠并重试
//            try {
//                Thread.sleep(50);
//            } catch (InterruptedException e) {
//                e.printStackTrace();
//            }
//            return queryWithMutex(id);
//        }
//        Merchant merchant = null;
//        try {
//            merchant = getById(id);
//        }
//        finally {
//            unLock(lock);
//        }
//        if (merchant == null) {
//            stringRedisTemplate.opsForValue().set(CACHE_MERCHANT_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
//        }
//        stringRedisTemplate.opsForValue().set(CACHE_MERCHANT_KEY + id, JSONUtil.toJsonStr(merchant), CACHE_MERCHANT_TTL, TimeUnit.MINUTES);
//        return merchant;
//    }

//    private Merchant queryWithPassThrough(Long id) {
//        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_MERCHANT_KEY + id);
//        if (StringUtils.isNotBlank(shopJson)) {
//            Merchant merchant = BeanUtil.toBean(shopJson, Merchant.class);
//            return merchant;
//        }
//        if (shopJson != null) {
//            return null;
//        }
//        Merchant merchant = getById(id);
//        if (merchant == null) {
//            stringRedisTemplate.opsForValue().set(CACHE_MERCHANT_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
//            return null;
//        }
//        stringRedisTemplate.opsForValue().set(CACHE_MERCHANT_KEY + id, JSONUtil.toJsonStr(merchant), CACHE_MERCHANT_TTL, TimeUnit.MINUTES);
//        return merchant;
//    }



    @Override
    @Transactional
    public Result updateShop(Merchant merchant) {
        Long id = merchant.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        authorizationService.requireMerchantOwner(id);
        // The owner is immutable through the ordinary merchant edit path.
        merchant.setOwnerUserId(null);
        Merchant before = getById(id);
        //更新数据库
        updateById(merchant);
        Merchant current = getById(id);
        registerCacheInvalidation(id, before == null ? null : before.getTypeId(),
                current == null ? merchant.getTypeId()
                        : current.getTypeId(), current == null ? merchant : current);
        //返回结果
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //判断是否需要根据坐标查询
        if (x == null || y == null) {
            Page<Merchant> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }
        //计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        //查询redis
        String key = MERCHANT_GEO_KEY + typeId;
        // 使用 GEORADIUS 替代 GEOSEARCH
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .radius(key,
                        new Circle(new Point(x, y), new Distance(5000)),
                        RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
                                .includeDistance()
                                .limit(end));
//        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
//                .search(key,
//                        GeoReference.fromCoordinate(x, y),
//                        new Distance(5000),
//                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeCoordinates().includeDistance().limit(end));
        if(results == null){
            // GEO 数据未预热（商户未通过应用写入过坐标）：回源数据库，保证列表可展示
            return Result.ok(queryByTypeFromDb(typeId, current));
        }

        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        //解析出对应的店铺id 以及店铺distance
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip( from).forEach(
                geoResult -> {
                    //获取店铺id
                    String shopId = geoResult.getContent() == null ? null : geoResult.getContent().getName();
                    // Redis GEO members are untrusted; ignore malformed
                    // members instead of throwing or concatenating them into
                    // the dynamic FIELD ordering clause below.
                    if (shopId == null || !shopId.matches("\\d+")) {
                        return;
                    }
                    try {
                        ids.add(Long.valueOf(shopId));
                    } catch (NumberFormatException ignored) {
                        return;
                    }
                    Distance distance = geoResult.getDistance();
                    distanceMap.put(shopId, distance);
                }
        );
        // 检查并过滤空的 ID 列表
        if (CollectionUtils.isEmpty(ids)) {
            // GEO 中无该类型商户：回源数据库，避免前端列表空白
            return Result.ok(queryByTypeFromDb(typeId, current));
        }

        //查询数据库
        String idStr = StrUtil.join(",", ids);
        List<Merchant> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Merchant merchant : shops) {
            Distance distance = merchant.getId() == null ? null : distanceMap.get(merchant.getId().toString());
            if (distance != null) {
                merchant.setDistance(distance.getValue());
            }
        }

        return Result.ok(shops);
    }

    /** 按类型分页查询商户（回源数据库，GEO 数据未预热时的降级路径） */
    private List<Merchant> queryByTypeFromDb(Integer typeId, Integer current) {
        Page<Merchant> page = query()
                .eq("type_id", typeId)
                .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
        return page.getRecords();
    }

    @Override
    public Result queryNearby(Double x, Double y, Integer current) {
        int pageSize = SystemConstants.MAX_PAGE_SIZE;
        if (current == null || current < 1) {
            throw new com.campusdeal.exception.ValidationException("页码必须从1开始");
        }
        List<Merchant> all;
        if (x == null || y == null) {
            all = query().page(new Page<>(current, pageSize)).getRecords();
        } else {
            int candidateLimit = Math.min(Math.max(current * pageSize * 4, pageSize), 2000);
            all = queryNearbyFromGeo(x, y, candidateLimit);
            if (all.isEmpty()) {
                // GEO is an acceleration index and may be cold after a Redis
                // loss. Fall back to an indexed bounding box, still bounded
                // before exact distance sorting.
                double latDelta = 5000.0 / 111_000.0;
                double lonDelta = latDelta / Math.max(Math.cos(Math.toRadians(y)), 0.1);
                all = query().ge("y", y - latDelta).le("y", y + latDelta)
                        .ge("x", x - lonDelta).le("x", x + lonDelta)
                        .last("LIMIT " + candidateLimit).list();
            }
        }
        if (CollectionUtils.isEmpty(all)) {
            return Result.ok(Collections.emptyList());
        }
        // 带坐标时按距离升序（无坐标按默认顺序）
        if (x != null && y != null) {
            // Mapper implementations and test doubles are allowed to return an
            // immutable list (for example List.of(...)); copy before sorting.
            all = new ArrayList<>(all);
            all.forEach(m -> m.setDistance(calcDistanceMeters(x, y, m.getX(), m.getY())));
            all.sort(Comparator.comparingDouble(
                    m -> m.getDistance() == null ? Double.MAX_VALUE : m.getDistance()));
        }
        int from = (current - 1) * pageSize;
        int end = Math.min(current * pageSize, all.size());
        if (from >= all.size()) {
            return Result.ok(Collections.emptyList());
        }
        return Result.ok(new ArrayList<>(all.subList(from, end)));
    }

    private List<Merchant> queryNearbyFromGeo(Double x, Double y, int candidateLimit) {
        try {
            GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                    .radius(MERCHANT_GEO_ALL_KEY, new Circle(new Point(x, y), new Distance(5000)),
                            RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
                                    .includeDistance().sortAscending().limit(candidateLimit));
            if (results == null || results.getContent().isEmpty()) return Collections.emptyList();
            List<String> ids = results.getContent().stream()
                    .map(GeoResult::getContent)
                    .map(RedisGeoCommands.GeoLocation::getName)
                    // GEO members are untrusted Redis data. Keep the dynamic
                    // FIELD ordering clause numeric-only so a corrupted or
                    // poisoned member can never become SQL text.
                    .filter(Objects::nonNull)
                    .filter(id -> id.matches("\\d+"))
                    .toList();
            if (ids.isEmpty()) return Collections.emptyList();
            String idStr = StrUtil.join(",", ids);
            List<Merchant> merchants = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
            Map<String, Distance> distances = results.getContent().stream()
                    .filter(result -> result.getContent() != null && result.getDistance() != null)
                    .collect(Collectors.toMap(result -> result.getContent().getName(),
                            GeoResult::getDistance, (left, right) -> left, LinkedHashMap::new));
            merchants.forEach(merchant -> {
                Distance distance = distances.get(String.valueOf(merchant.getId()));
                if (distance != null) merchant.setDistance(distance.getValue());
            });
            return merchants;
        } catch (Exception e) {
            // Redis GEO is optional; the caller will use the indexed DB path.
            return Collections.emptyList();
        }
    }

    private void registerCacheInvalidation(Long merchantId, Long oldTypeId, Long newTypeId, Merchant geoMerchant) {
        Runnable invalidate = () -> {
            stringRedisTemplate.delete(CACHE_MERCHANT_KEY + merchantId);
            if (oldTypeId != null) {
                stringRedisTemplate.delete(MERCHANT_GEO_KEY + oldTypeId);
            }
            if (newTypeId != null && !Objects.equals(oldTypeId, newTypeId)) {
                stringRedisTemplate.delete(MERCHANT_GEO_KEY + newTypeId);
            }
            // Rebuild the bounded GEO indexes after the transaction commits.
            stringRedisTemplate.delete(MERCHANT_GEO_ALL_KEY);
            if (geoMerchant != null && geoMerchant.getX() != null && geoMerchant.getY() != null) {
                Point point = new Point(geoMerchant.getX(), geoMerchant.getY());
                stringRedisTemplate.opsForGeo().add(MERCHANT_GEO_ALL_KEY, point, String.valueOf(merchantId));
                if (geoMerchant.getTypeId() != null) {
                    stringRedisTemplate.opsForGeo().add(MERCHANT_GEO_KEY + geoMerchant.getTypeId(),
                            point, String.valueOf(merchantId));
                }
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    invalidate.run();
                }
            });
        } else {
            invalidate.run();
        }
    }

    /**
     * 简化球面距离（米）：纬度差 × 111km，经度差 × 111km × cos(lat)。仅用于附近排序展示。
     */
    private Double calcDistanceMeters(Double x1, Double y1, Double x2, Double y2) {
        if (x2 == null || y2 == null) return null;
        double dx = (x1 - x2) * 111.0 * 1000.0 * Math.cos(Math.toRadians(y1));
        double dy = (y1 - y2) * 111.0 * 1000.0;
        return (double) Math.round(Math.sqrt(dx * dx + dy * dy));
    }
}
