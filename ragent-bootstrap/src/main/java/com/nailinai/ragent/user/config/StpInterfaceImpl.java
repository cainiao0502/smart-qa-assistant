package com.nailinai.ragent.user.config;

import cn.dev33.satoken.stp.StpInterface;
import com.nailinai.ragent.user.entity.User;
import com.nailinai.ragent.user.mapper.UserMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Sa-Token 角色数据源。
 *
 * <p>此前项目里 {@code t_user.role} 只存不用：{@code StpUtil.checkRole} 依赖
 * {@link StpInterface} 提供角色列表，而项目从未实现它 —— 导致任何角色校验
 * （例如 {@code checkRole("admin")}）都会因为拿不到角色而全部拒绝。
 * 本实现让「按角色管控」真正可用。</p>
 *
 * <p>当前只有 {@code admin} / {@code user} 两个角色；权限粒度暂时留空，
 * 高危接口（MCP / Skill 管理）通过 {@code checkRole("admin")} 守住。</p>
 */
@Component
public class StpInterfaceImpl implements StpInterface {

    private final UserMapper userMapper;

    public StpInterfaceImpl(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        User user = userMapper.selectById(Long.parseLong(loginId.toString()));
        if (user == null || user.getRole() == null) {
            return List.of();
        }
        return List.of(user.getRole());
    }

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        // 项目目前以角色为唯一管控粒度，未启用细粒度权限点
        return List.of();
    }
}
