package com.nailinai.ragent.user.service;

import com.nailinai.ragent.framework.common.BusinessException;
import com.nailinai.ragent.framework.common.ErrorCode;
import com.nailinai.ragent.user.dto.LoginRequest;
import com.nailinai.ragent.user.dto.LoginResponse;
import com.nailinai.ragent.user.dto.RegisterRequest;
import com.nailinai.ragent.user.dto.UserInfoResponse;
import com.nailinai.ragent.user.entity.User;
import com.nailinai.ragent.user.mapper.UserMapper;
import cn.dev33.satoken.secure.BCrypt;
import cn.dev33.satoken.stp.StpUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    private static final String DEFAULT_ROLE = "user";

    private final UserMapper userMapper;

    public UserService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public LoginResponse register(RegisterRequest request) {
        User existing = userMapper.selectByUsername(request.getUsername());
        if (existing != null) {
            throw new BusinessException(ErrorCode.CONFLICT, "用户名已存在");
        }
        User user = new User();
        user.setUsername(request.getUsername());
        user.setPasswordHash(BCrypt.hashpw(request.getPassword()));
        user.setRole(DEFAULT_ROLE);
        userMapper.insert(user);
        log.info("User registered: id={}, username={}", user.getId(), user.getUsername());
        return login(buildLoginRequest(request.getUsername(), request.getPassword()));
    }

    public LoginResponse login(LoginRequest request) {
        User user = userMapper.selectByUsername(request.getUsername());
        if (user == null || !BCrypt.checkpw(request.getPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        StpUtil.login(user.getId());
        // 角色写入登录会话：UserContext.currentRole() 与前端按角色渲染菜单都从这里取
        StpUtil.getSession().set("role", user.getRole() == null ? DEFAULT_ROLE : user.getRole());
        String token = StpUtil.getTokenValue();
        log.info("User login: id={}, username={}", user.getId(), user.getUsername());
        return new LoginResponse(token, user.getId(), user.getUsername(), user.getRole());
    }

    public void logout() {
        StpUtil.logout();
        log.info("User logout: id={}", StpUtil.getLoginIdAsLong());
    }

    public UserInfoResponse currentUser() {
        Long userId = StpUtil.getLoginIdAsLong();
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        UserInfoResponse resp = new UserInfoResponse();
        resp.setUserId(user.getId());
        resp.setUsername(user.getUsername());
        resp.setRole(user.getRole());
        return resp;
    }

    public Long currentUserId() {
        return StpUtil.getLoginIdAsLong();
    }

    public boolean isFirstUser() {
        return userMapper.count() == 0;
    }

    private LoginRequest buildLoginRequest(String username, String password) {
        LoginRequest req = new LoginRequest();
        req.setUsername(username);
        req.setPassword(password);
        return req;
    }
}