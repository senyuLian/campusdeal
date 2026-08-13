package com.campusdeal.config;

import com.campusdeal.utils.LoginInterceptor;
import com.campusdeal.utils.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import jakarta.annotation.Resource;

@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/user/code",
                        "/user/login",
                        // 帖子公开浏览：热门 / 详情 / 点赞列表 / 他人主页帖子
                        "/post/hot",
                        "/post/*",
                        "/post/likes/*",
                        "/post/of/user",
                        // 用户公开主页（GET /user/public/{id}）
                        "/user/public/*",
                        "/merchant/**",
                        "/merchant-type/**",
                        "/upload/**",
                        "/coupon/**",
                        // Module 06：聊天 UI 静态资源（页面内通过 fetch 携带 token 调 /agent/**）
                        "/chat.html",
                        "/css/**",
                        "/js/**"
                ).order(2);
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate))
                .addPathPatterns("/**").order(0);
    }
}
