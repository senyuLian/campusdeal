package com.campusdeal.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.campusdeal.dto.LoginFormDTO;
import com.campusdeal.dto.Result;
import com.campusdeal.dto.UserDTO;
import com.campusdeal.entity.User;
import com.campusdeal.mapper.UserMapper;
import com.campusdeal.service.IUserService;
import com.campusdeal.exception.RateLimitException;
import com.campusdeal.security.SecurityProperties;
import com.campusdeal.utils.RegexUtils;
import com.campusdeal.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.Collections;

import static com.campusdeal.utils.RedisConstants.*;
import static com.campusdeal.utils.SystemConstants.USER_NICK_NAME_PREFIX;

@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private SecurityProperties securityProperties;

    private static final String RATE_WINDOW_LUA = """
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            return count
            """;
    private final DefaultRedisScript<Long> rateWindowScript = new DefaultRedisScript<>(RATE_WINDOW_LUA, Long.class);

    private static final String VERIFY_AND_DELETE_LUA = """
            local value = redis.call('GET', KEYS[1])
            if value and value == ARGV[1] then
                redis.call('DEL', KEYS[1])
                return 1
            end
            return 0
            """;
    private final DefaultRedisScript<Long> verifyAndDeleteScript =
            new DefaultRedisScript<>(VERIFY_AND_DELETE_LUA, Long.class);

    @Override
    public Result sendCode(String phone, String origin) {
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("INVALID_PHONE", "Invalid phone number");
        }
        enforceRate("login:code:rate:phone:" + phone, securityProperties.getVerificationSendPerMinute());
        enforceRate("login:code:rate:origin:" + safeOrigin(origin), securityProperties.getVerificationSendPerMinute() * 3);
        String code = RandomUtil.randomNumbers(6);
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        return Result.ok("Verification code sent");
    }

    @Override
    public Result login(LoginFormDTO loginForm, String origin) {
        if (loginForm == null) {
            throw new com.campusdeal.exception.ValidationException("登录参数不能为空");
        }
        String phone = loginForm.getPhone();
        String code = loginForm.getCode();

        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("INVALID_PHONE", "Invalid phone number");
        }
        enforceRate("login:verify:rate:phone:" + phone, securityProperties.getVerificationAttemptPerMinute());
        enforceRate("login:verify:rate:origin:" + safeOrigin(origin), securityProperties.getVerificationAttemptPerMinute() * 3);
        Long verified = stringRedisTemplate.execute(verifyAndDeleteScript,
                Collections.singletonList(LOGIN_CODE_KEY + phone), code == null ? "" : code);
        if (!Long.valueOf(1L).equals(verified)) {
            return Result.fail("INVALID_CODE", "Invalid verification code");
        }

        String token = UUID.randomUUID(true).toString(true);

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

    private void enforceRate(String key, int limit) {
        int safeLimit = Math.max(1, limit);
        Long count = stringRedisTemplate.execute(rateWindowScript,
                Collections.singletonList(key), "60");
        if (count != null && count > safeLimit) {
            throw new RateLimitException("请求过于频繁，请稍后再试");
        }
    }

    private String safeOrigin(String origin) {
        if (origin == null || origin.isBlank()) {
            return "unknown";
        }
        return origin.length() > 64 ? origin.substring(0, 64) : origin;
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
        log.debug("User logout completed");
        return Result.ok();
    }
}
