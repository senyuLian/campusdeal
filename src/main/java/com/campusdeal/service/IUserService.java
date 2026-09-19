package com.campusdeal.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.campusdeal.dto.LoginFormDTO;
import com.campusdeal.dto.Result;
import com.campusdeal.entity.User;

//import javax.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSession;


/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IUserService extends IService<User> {

    default Result sendCode(String phone) {
        return sendCode(phone, "unknown");
    }

    Result sendCode(String phone, String origin);

    default Result login(LoginFormDTO loginForm) {
        return login(loginForm, "unknown");
    }

    Result login(LoginFormDTO loginForm, String origin);

    Result sign();

    Result signCount();

    Result logout(String token);
}
