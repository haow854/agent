package com.javaee.user.controller;

import com.javaee.common.model.Result;
import com.javaee.common.security.RequestUserContext;
import com.javaee.user.dto.LoginDTO;
import com.javaee.user.dto.RegisterDTO;
import com.javaee.user.entity.User;
import com.javaee.user.service.UserService;
import com.javaee.user.vo.LoginVO;
import com.javaee.user.vo.UserVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * @author qxk
 * @description: 用户控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/users")
@Tag(name = "用户管理", description = "用户登录、注册、信息管理等接口")
public class UserController {

    @Autowired
    private UserService userService;

    @Autowired
    private RequestUserContext requestUserContext;

    /**
     * 用户登录
     * @param loginDTO 登录信息
     * @return 登录结果
     */
    @PostMapping("/login")
    @Operation(summary = "用户登录", description = "根据用户名和密码进行登录，返回访问令牌和刷新令牌")
    public Result<LoginVO> login(@RequestBody LoginDTO loginDTO) {
        log.info("用户登录: {}", loginDTO.getUsername());
        LoginVO loginVO = userService.login(loginDTO);
        return Result.success(loginVO);
    }

    /**
     * 用户注册
     * @param registerDTO 注册信息
     * @return 注册结果
     */
    @PostMapping("/register")
    @Operation(summary = "用户注册", description = "创建新用户，返回用户信息")
    public Result<UserVO> register(@RequestBody RegisterDTO registerDTO) {
        log.info("用户注册: {}", registerDTO.getUsername());
        UserVO userVO = userService.register(registerDTO);
        return Result.success(userVO);
    }

    /**
     * 获取用户信息
     * @param id 用户ID
     * @return 用户信息
     */
    @GetMapping("/{id:\\d+}")
    @Operation(summary = "获取用户信息", description = "根据用户ID获取用户详细信息")
    public Result<UserVO> getUserById(@Parameter(description = "用户ID") @PathVariable Long id) {
        log.info("获取用户信息: {}", id);
        return Result.success(visibleToCaller(userService.getUserById(id)));
    }

    /**
     * 按用户名查找用户，分享文档时找协作者用
     * @param username 用户名
     * @return 用户信息（不是本人时只有 id 和用户名）
     */
    @GetMapping("/lookup")
    @Operation(summary = "根据用户名查询用户", description = "用于文档协作授权时查找协作者")
    public Result<UserVO> getUserByUsername(@Parameter(description = "用户名") @RequestParam String username) {
        log.info("根据用户名查询用户: {}", username);
        User user = userService.getUserByUsername(username);
        if (user == null) {
            throw new com.javaee.common.exception.BusinessException(com.javaee.common.constant.ErrorCodeEnum.USER_NOT_FOUND);
        }
        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(user, userVO);
        return Result.success(visibleToCaller(userVO));
    }

    /**
     * 别人的资料只给 id 和用户名，分享文档、显示协作者用这两项就够了；
     * 邮箱、手机号这些只有本人和管理员看得到，否则按 id 遍历就能拿到所有人的联系方式。
     */
    private UserVO visibleToCaller(UserVO user) {
        if (user.getId().equals(requestUserContext.getRequiredUserId()) || requestUserContext.isAdmin()) {
            return user;
        }
        UserVO visible = new UserVO();
        visible.setId(user.getId());
        visible.setUsername(user.getUsername());
        return visible;
    }

    /**
     * 用刷新令牌换新的访问令牌
     * @param refreshToken 刷新令牌（访问令牌不行）
     * @return 新的访问令牌
     */
    @PostMapping("/refresh")
    @Operation(summary = "刷新令牌", description = "使用刷新令牌获取新的访问令牌")
    public Result<String> refreshToken(@Parameter(description = "刷新令牌") @RequestParam String refreshToken) {
        log.info("刷新令牌");
        String accessToken = userService.refreshToken(refreshToken);
        return Result.success(accessToken);
    }

    /**
     * 登出
     * @return 登出结果
     */
    @PostMapping("/logout")
    @Operation(summary = "用户登出", description = "用户登出操作")
    public Result<Void> logout() {
        log.info("用户登出");
        // 前端清除本地存储的令牌
        return Result.success();
    }
}
