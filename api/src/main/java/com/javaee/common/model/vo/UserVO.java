package com.javaee.common.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;
import java.util.List;

/**
 * @author qxk
 * @description: 用户VO（展示）
 */
@Data
public class UserVO implements Serializable {
    private static final long serialVersionUID = 1L;

    // 用户ID
    private Long id;

    // 用户名
    private String username;

    // 邮箱
    private String email;

    // 手机号
    private String phone;

    // 真实姓名
    private String realName;

    // 性别（0-未知，1-男，2-女）
    private Integer gender;

    // 年龄
    private Integer age;

    // 头像
    private String avatar;

    // 角色列表
    private List<String> roles;

    // 权限列表
    private List<String> permissions;

    // 创建时间
    private Date createTime;

    // 更新时间
    private Date updateTime;

    // 最后登录时间
    private Date lastLoginTime;

    // 状态（0-禁用，1-启用）
    private Integer status;

    // 备注
    private String remark;
}
