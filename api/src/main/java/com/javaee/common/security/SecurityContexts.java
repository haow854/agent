package com.javaee.common.security;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.concurrent.Callable;

/** 把身份带到本不持有它的线程上（队列消费、工具批执行）：设→跑→清，只此一份。 */
public final class SecurityContexts {

    private SecurityContexts() {
    }

    public static <T> T callAs(SecurityContext context, Callable<T> action) throws Exception {
        SecurityContextHolder.setContext(context);
        try {
            return action.call();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /** 以某用户身份行事的上下文，给没有请求线程的场景用。 */
    public static SecurityContext userContext(Long userId) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(
                String.valueOf(userId), null,
                List.of(new SimpleGrantedAuthority("ROLE_user"))));
        return context;
    }
}
