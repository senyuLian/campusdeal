package com.campusdeal.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.interfaces.Func;
import com.campusdeal.entity.MerchantType;
import com.campusdeal.mapper.MerchantTypeMapper;
import com.campusdeal.service.IMerchantTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;



/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class MerchantTypeServiceImpl extends ServiceImpl<MerchantTypeMapper, MerchantType> implements IMerchantTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    @Override
    public List<MerchantType> queryList() {
        String key = "cache:merchant:type";
        if (stringRedisTemplate.hasKey(key)) {
            List<String> typeList = stringRedisTemplate.opsForList().range(key, 0, -1);
            log.debug("从redis中获取数据{}", typeList);
            if (typeList != null) {
                List<MerchantType> shopTypes = typeList.stream().map(type -> JSONUtil.toBean(type, MerchantType.class)).collect(Collectors.toList());
                log.debug("成功转换{}条数据", shopTypes);
                return shopTypes;
            }
        }
        List<MerchantType> list = query().orderByAsc("sort").list();
        if (list == null) {
            return null;
        }
        stringRedisTemplate.opsForList().leftPushAll(key, list.stream().map(type -> JSONUtil.toJsonStr(type)).collect(Collectors.toList()));
        return list;
    }
}
