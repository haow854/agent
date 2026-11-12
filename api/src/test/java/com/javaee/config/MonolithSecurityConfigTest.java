package com.javaee.config;

import com.javaee.common.config.security.JwtAuthenticationFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the anonymous-access allowlist. No controllers are registered, so a path that
 * clears the filter chain reaches dispatch and 404s. That makes the two outcomes
 * unambiguous: 403 means the chain rejected the request, 404 means it let it through.
 */
@WebMvcTest
@Import({MonolithSecurityConfig.class, JwtAuthenticationFilter.class})
class MonolithSecurityConfigTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void businessApisRejectAnonymousCallers() throws Exception {
        mockMvc.perform(get("/api/documents/doc-1")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/files/download/file-1")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/ai/agent/tools")).andExpect(status().isForbidden());
    }

    @Test
    void recoveringSomeoneElsesTaskIsNoLongerAnonymous() throws Exception {
        mockMvc.perform(post("/api/ai/agent/tasks/task-1/recover"))
                .andExpect(status().isForbidden());
    }

    @Test
    void userLookupIsNoLongerAnonymousButSigningInStillIs() throws Exception {
        mockMvc.perform(get("/api/users/1")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/users/lookup").param("username", "alice"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/users/login")).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/users/register")).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/users/refresh")).andExpect(status().isNotFound());
    }

    @Test
    void loginPathsAreOpenedByMethodNotByPrefix() throws Exception {
        mockMvc.perform(get("/api/users/login")).andExpect(status().isForbidden());
    }

    @Test
    void healthIsOpenButTheRestOfActuatorIsNot() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isNotFound());
        mockMvc.perform(get("/actuator/env")).andExpect(status().isForbidden());
    }
}
