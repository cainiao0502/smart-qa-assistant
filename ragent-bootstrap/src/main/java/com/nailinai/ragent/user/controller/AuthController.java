package com.nailinai.ragent.user.controller;

import com.nailinai.ragent.framework.common.Result;
import com.nailinai.ragent.user.dto.LoginRequest;
import com.nailinai.ragent.user.dto.LoginResponse;
import com.nailinai.ragent.user.dto.RegisterRequest;
import com.nailinai.ragent.user.dto.UserInfoResponse;
import com.nailinai.ragent.user.service.UserService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/register")
    public Result<LoginResponse> register(@Valid @RequestBody RegisterRequest request) {
        return Result.success(userService.register(request));
    }

    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return Result.success(userService.login(request));
    }

    @PostMapping("/logout")
    public Result<Void> logout() {
        userService.logout();
        return Result.success();
    }

    @GetMapping("/me")
    public Result<UserInfoResponse> currentUser() {
        return Result.success(userService.currentUser());
    }
}