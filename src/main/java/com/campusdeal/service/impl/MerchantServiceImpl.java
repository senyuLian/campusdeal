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
        //更新数据库
        updateById(merchant);
        //删除缓存
        stringRedisTemplate.delete(CACHE_MERCHANT_KEY + merchant.getId());
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
                    String shopId = geoResult.getContent().getName();
                    ids.add(Long.valueOf(shopId));
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
            merchant.setDistance(distanceMap.get(merchant.getId().toString()).getValue());
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
        List<Merchant> all = query().list();
        if (CollectionUtils.isEmpty(all)) {
            return Result.ok(Collections.emptyList());
        }
        // 带坐标时按距离升序（无坐标按默认顺序）
        if (x != null && y != null) {
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
