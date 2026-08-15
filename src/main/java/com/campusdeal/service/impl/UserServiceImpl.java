package com.campusdeal.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.log.Log;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.campusdeal.dto.LoginFormDTO;
import com.campusdeal.dto.Result;
import com.campusdeal.dto.UserDTO;
import com.campusdeal.entity.User;
import com.campusdeal.mapper.UserMapper;
import com.campusdeal.service.IUserService;
import com.campusdeal.utils.RegexUtils;
import com.campusdeal.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.campusdeal.utils.RedisConstants.*;
import static com.campusdeal.utils.SystemConstants.USER_NICK_NAME_PREFIX;

@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone) {
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("Invalid phone number");
        }
        String code = RandomUtil.randomNumbers(6);
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        log.debug("Send verification code: {}", code);
        return Result.ok("Verification code sent");
    }

    @Override
    public Result login(LoginFormDTO loginForm) {
        String phone = loginForm.getPhone();
        String code = loginForm.getCode();
        String redisCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);

        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("Invalid phone number");
        }
        // Fixed: changed && to || with proper null check
        if (redisCode == null || !redisCode.equals(code)) {
            return Result.fail("Invalid verification code");
        }

        String token = UUID.randomUUID(true).toString(true);
        log.debug("Generated token: {}", token);

        User user = query().eq("phone", phone).one();
        if (user == null) {
            user = new User();
            user.setPhone(phone);
            user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
            save(user);
        }

        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token,
                BeanUtil.beanToMap(userDTO, new HashMap<>(),
                        CopyOptions.create()
                                .setIgnoreNullValue(true)
                                .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString())));
        stringRedisTemplate.expire(LOGIN_USER_KEY + token, LOGIN_USER_TTL, TimeUnit.SECONDS);

        return Result.ok(token);
    }

    @Override
    public Result sign() {
        Long userId = UserHolder.getUser().getId();
        LocalDate now = LocalDate.now();
        // 用 getMonthValue() 取数字月份；getMonth() 返回 Month 枚举，toString 为英文名（AUGUST），与约定 sign:{userId}:{year}:{month} 不符
        String key = USER_SIGN_KEY + userId + ":" + now.getYear() + ":" + now.getMonthValue();
        int day = now.getDayOfMonth();
        stringRedisTemplate.opsForValue().setBit(key, day - 1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        Long userId = UserHolder.getUser().getId();
        LocalDate now = LocalDate.now();
        int day = now.getDayOfMonth();
        String key = USER_SIGN_KEY + userId + ":" + now.getYear() + ":" + now.getMonthValue();
        List<Long> longs = stringRedisTemplate.opsForValue().bitField(key,
                BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(day)).valueAt(0));
        if (longs == null || longs.isEmpty()) {
            return Result.ok(0);
        }
        long signCount = longs.get(0);
        if (signCount == 0) {
            return Result.ok(0);
        }
        int count = 0;
        while (true) {
            if ((signCount & 1) == 0) {
                break;
            } else {
                count++;
            }
            signCount >>= 1;
        }
        return Result.ok(count);
    }

    @Override
    public Result logout(String token) {
        // Delete the Redis token to invalidate the session
        stringRedisTemplate.delete(LOGIN_USER_KEY + token);
        UserHolder.removeUser();
        log.debug("User logged out, token invalidated: {}", token);
        return Result.ok();
    }
}
