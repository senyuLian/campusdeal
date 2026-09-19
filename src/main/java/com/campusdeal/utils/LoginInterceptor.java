//package com.campusdeal.utils;
//
//import cn.hutool.core.bean.BeanUtil;
//import com.campusdeal.dto.UserDTO;
//import com.campusdeal.entity.User;
//import org.springframework.beans.BeanUtils;
//import org.springframework.data.redis.core.StringRedisTemplate;
//import org.springframework.web.servlet.HandlerInterceptor;
//
//import javax.servlet.http.HttpServletRequest;
//import javax.servlet.http.HttpServletResponse;
//import javax.servlet.http.HttpSession;
//import java.util.Map;
//import java.util.concurrent.TimeUnit;
//
//public class LoginInterceptor implements HandlerInterceptor {
//
//
//
//    @Override
//    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
//        if(UserHolder.getUser() == null){
//            response.setStatus(401);
//            return false;
//        }
//        return true;
//    }
//}
package com.campusdeal.utils;

import cn.hutool.core.bean.BeanUtil;
import com.campusdeal.dto.UserDTO;
import com.campusdeal.entity.User;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if(UserHolder.getUser() == null){
            response.setStatus(401);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"success\":false,\"errorCode\":\"UNAUTHORIZED\",\"errorMsg\":\"请先登录\"}");
            return false;
        }
        return true;
    }
}
