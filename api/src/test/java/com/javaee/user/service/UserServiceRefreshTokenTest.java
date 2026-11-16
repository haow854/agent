package com.javaee.user.service;

import com.javaee.common.exception.BusinessException;
import com.javaee.common.utils.JwtUtils;
import com.javaee.user.entity.User;
import com.javaee.user.mapper.UserMapper;
import com.javaee.user.service.impl.UserServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 刷新接口只认刷新令牌：拿一个访问令牌来，就能把 30 分钟的令牌无限续下去。 */
class UserServiceRefreshTokenTest {

    private final UserMapper mapper = mock(UserMapper.class);
    private final UserServiceImpl service = new UserServiceImpl();

    @BeforeEach
    void setUp() {
        new JwtUtils("jwt-unit-tests-only-signing-key-0123456789");
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        User user = new User();
        user.setId(42L);
        user.setUsername("alice");
        user.setRole("user");
        when(mapper.selectById(42L)).thenReturn(user);
    }

    @Test
    void aRefreshTokenGetsANewAccessToken() {
        String access = service.refreshToken(JwtUtils.generateRefreshToken(42L));

        assertThat(JwtUtils.parseAccessToken(access).get("userId", Long.class)).isEqualTo(42L);
    }

    @Test
    void anAccessTokenCannotBeUsedToRefresh() {
        String access = JwtUtils.generateToken(42L, "alice", "user");

        assertThatThrownBy(() -> service.refreshToken(access)).isInstanceOf(BusinessException.class);
    }
}
