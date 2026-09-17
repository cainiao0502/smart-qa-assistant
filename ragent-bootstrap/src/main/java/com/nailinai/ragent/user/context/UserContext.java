package com.nailinai.ragent.user.context;

import cn.dev33.satoken.stp.StpUtil;

public final class UserContext {

    private UserContext() {
    }

    public static Long currentUserId() {
        return StpUtil.getLoginIdAsLong();
    }

    public static boolean isLoggedIn() {
        return StpUtil.isLogin();
    }

    public static String currentRole() {
        if (!StpUtil.isLogin()) {
            return null;
        }
        Object role = StpUtil.getSession().get("role");
        return role instanceof String ? (String) role : null;
    }
}