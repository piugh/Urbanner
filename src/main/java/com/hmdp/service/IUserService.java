package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;

import javax.servlet.http.HttpSession;

/**
 * <p>
 *  服务类
 * </p>
 */
public interface IUserService extends IService<User> {

    /**
     * 发送验证码
     * @param phone
     */
    Result sendCode(String phone);

    /**
     * 登陆操作
     * @param loginForm
     * @return
     */
    Result login(LoginFormDTO loginForm);

    /**
     * 退出登录
     * @return
     */
    Result logout();

}
