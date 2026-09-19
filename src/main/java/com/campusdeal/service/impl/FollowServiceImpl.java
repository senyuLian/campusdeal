package com.campusdeal.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.campusdeal.dto.Result;
import com.campusdeal.dto.UserDTO;
import com.campusdeal.entity.Follow;
import com.campusdeal.mapper.FollowMapper;
import com.campusdeal.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.campusdeal.service.IUserService;
import com.campusdeal.utils.UserHolder;
import com.campusdeal.security.AuthorizationService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import jakarta.annotation.Resource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    @Resource
    private AuthorizationService authorizationService;

    @Override
    @Transactional
    public Result follow(Long followUserId, Boolean isFollow) {
        //获取当前用户
        Long userId = authorizationService.requireAuthenticated().getId();
        if (followUserId == null || isFollow == null) {
            throw new com.campusdeal.exception.ValidationException("关注参数不合法");
        }
        if (Boolean.TRUE.equals(isFollow) && userId.equals(followUserId)) {
            throw new com.campusdeal.exception.ValidationException("不能关注自己");
        }

        //判断当前用户是取关还是关注
        Runnable mirror = Boolean.TRUE.equals(isFollow)
                ? () -> stringRedisTemplate.opsForSet().add("follows:" + userId, followUserId.toString())
                : () -> stringRedisTemplate.opsForSet().remove("follows:" + userId, followUserId.toString());
        if (Boolean.TRUE.equals(isFollow)) {
            getBaseMapper().insertIgnore(userId, followUserId);
        } else {
            remove(new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id", followUserId));
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    mirror.run();
                }
            });
        } else {
            mirror.run();
        }

        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        Long userId = authorizationService.requireAuthenticated().getId();
        Long count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        return Result.ok(count > 0);
    }

    @Override
    public Result followCommons(Long followUserId) {
        Long userId = authorizationService.requireAuthenticated().getId();
        String key = "follows:" + userId;
        String key2 = "follows:" + followUserId;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, key2);
        if (intersect == null || intersect.isEmpty()) {
            return Result.ok(new ArrayList<>());
        }
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        List<UserDTO> users = userService.listByIds(ids).stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(users);
    }
}
