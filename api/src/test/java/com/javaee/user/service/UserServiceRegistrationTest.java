package com.javaee.user.service;

import com.javaee.common.exception.BusinessException;
import com.javaee.user.dto.RegisterDTO;
import com.javaee.user.mapper.UserMapper;
import com.javaee.user.service.impl.UserServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** 线上关闭注册时，后端自己拒绝：不靠 nginx 挡，容器网络里直接调接口也建不了账号。 */
class UserServiceRegistrationTest {

    @Test
    void registrationIsRefusedWhenClosed() {
        UserMapper mapper = mock(UserMapper.class);
        UserServiceImpl service = new UserServiceImpl();
        ReflectionTestUtils.setField(service, "userMapper", mapper);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "registrationEnabled", false);
        RegisterDTO dto = new RegisterDTO();
        dto.setUsername("alice");
        dto.setPassword("secret-password");
        dto.setEmail("alice@example.com");
        dto.setPhone("13800000001");

        assertThatThrownBy(() -> service.register(dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未开放注册");
        verifyNoInteractions(mapper);
    }
}
