package com.javaee.common.utils;

import com.javaee.common.constant.CommonConstant;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.WeakKeyException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class JwtUtilsTest {
    private static final String TEST_SECRET = "jwt-unit-tests-only-signing-key-0123456789";

    @Test
    void springConfigurationSuppliesTheSigningKey() {
        new ApplicationContextRunner()
                .withUserConfiguration(JwtUtils.class)
                .withPropertyValues("custom.jwt.secret=" + TEST_SECRET)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    String token = JwtUtils.generateToken(42L, "alice", "user");
                    var claims = Jwts.parserBuilder()
                            .setSigningKey(Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8)))
                            .build().parseClaimsJws(token).getBody();
                    assertThat(claims.get(CommonConstant.TOKEN_CLAIM_USER_ID, Long.class)).isEqualTo(42L);
                    assertThat(claims.get(CommonConstant.TOKEN_CLAIM_ROLE, String.class)).isEqualTo("user");
                });
    }

    @Test
    void existingTokensRemainValidWhenTheConfiguredKeyIsUnchanged() {
        String existingToken = Jwts.builder()
                .claim(CommonConstant.TOKEN_CLAIM_USER_ID, 7L)
                .setExpiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        new JwtUtils(TEST_SECRET);

        assertThat(JwtUtils.getUserId(existingToken)).isEqualTo(7L);
    }

    @Test
    void blankSigningKeyIsRejected() {
        assertThatThrownBy(() -> new JwtUtils(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void weakSigningKeyIsRejected() {
        assertThatThrownBy(() -> new JwtUtils("too-short"))
                .isInstanceOf(WeakKeyException.class);
    }
}
