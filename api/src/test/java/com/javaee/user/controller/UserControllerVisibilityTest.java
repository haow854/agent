package com.javaee.user.controller;

import com.javaee.common.security.RequestUserContext;
import com.javaee.user.entity.User;
import com.javaee.user.service.UserService;
import com.javaee.user.vo.UserVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 别人的邮箱、手机号不能被查到：否则按 id 遍历就能拿到所有用户的联系方式。 */
class UserControllerVisibilityTest {

    private final UserService userService = mock(UserService.class);
    private final RequestUserContext context = mock(RequestUserContext.class);
    private final UserController controller = new UserController();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "userService", userService);
        ReflectionTestUtils.setField(controller, "requestUserContext", context);
        when(userService.getUserById(7L)).thenReturn(profile(7L, "bob"));
        User bob = new User();
        bob.setId(7L);
        bob.setUsername("bob");
        bob.setEmail("bob@example.com");
        bob.setPhone("13800000007");
        when(userService.getUserByUsername("bob")).thenReturn(bob);
    }

    @Test
    void othersSeeOnlyIdAndUsername() {
        when(context.getRequiredUserId()).thenReturn(1L);

        for (UserVO seen : new UserVO[]{
                controller.getUserById(7L).getData(),
                controller.getUserByUsername("bob").getData()}) {
            assertThat(seen.getId()).isEqualTo(7L);
            assertThat(seen.getUsername()).isEqualTo("bob");
            assertThat(seen.getEmail()).isNull();
            assertThat(seen.getPhone()).isNull();
        }
    }

    @Test
    void usersSeeTheirOwnFullProfile() {
        when(context.getRequiredUserId()).thenReturn(7L);

        assertThat(controller.getUserById(7L).getData().getEmail()).isEqualTo("bob@example.com");
        assertThat(controller.getUserById(7L).getData().getPhone()).isEqualTo("13800000007");
    }

    @Test
    void adminsSeeFullProfiles() {
        when(context.getRequiredUserId()).thenReturn(1L);
        when(context.isAdmin()).thenReturn(true);

        assertThat(controller.getUserById(7L).getData().getEmail()).isEqualTo("bob@example.com");
    }

    private UserVO profile(Long id, String username) {
        UserVO vo = new UserVO();
        vo.setId(id);
        vo.setUsername(username);
        vo.setEmail(username + "@example.com");
        vo.setPhone("13800000007");
        return vo;
    }
}
