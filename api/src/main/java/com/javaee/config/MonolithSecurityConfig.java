package com.javaee.config;

import com.javaee.common.config.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class MonolithSecurityConfig {

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(authorize -> authorize
                        // 拿不到 token 的入口，只有这三个。
                        .requestMatchers(HttpMethod.POST,
                                "/api/users/login",
                                "/api/users/register",
                                "/api/users/refresh"
                        ).permitAll()
                        // 静态页与错误页，下面没有控制器。
                        .requestMatchers(
                                "/",
                                "/index.html",
                                "/static/**",
                                "/public/**",
                                "/error"
                        ).permitAll()
                        // 只放行健康检查；其余 actuator 端点（env/heapdump 等）需要认证。
                        .requestMatchers("/actuator/health").permitAll()
                        // WebSocket 握手带不了令牌，这里放行；登录校验在 STOMP 的 CONNECT 帧上
                        // （StompAuthInterceptor），进度也只推给任务所属的用户。
                        .requestMatchers("/ws/**").permitAll()
                        // TODO：swagger 应按 profile 开关，不该在生产环境无条件放行。
                        .requestMatchers(
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/v3/api-docs/**",
                                "/api-docs/**"
                        ).permitAll()
                        // /api/users/** /api/files/** /api/documents/** /api/ai/** 全部落到这里。
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
