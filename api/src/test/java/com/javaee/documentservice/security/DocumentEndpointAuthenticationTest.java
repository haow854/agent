package com.javaee.documentservice.security;

import com.javaee.common.config.security.JwtAuthenticationFilter;
import com.javaee.common.security.RequestUserContext;
import com.javaee.common.utils.JwtUtils;
import com.javaee.config.MonolithSecurityConfig;
import com.javaee.documentservice.controller.DocumentController;
import com.javaee.documentservice.service.DocumentService;
import com.javaee.documentservice.vo.DocumentVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end check that an {@code X-User-Id} header cannot buy an identity on
 * {@code /api/documents/**}. Two independent defences have to hold: the filter chain
 * rejects the anonymous request, and {@link RequestUserContext} would ignore the header
 * even if it got through. This class covers the first; {@link RequestUserContextTest}
 * covers the second on its own, so reverting either one fails a test.
 */
@WebMvcTest(controllers = DocumentController.class,
        properties = "custom.jwt.secret=document-endpoint-tests-only-signing-key-0123456789")
@Import({DocumentController.class, MonolithSecurityConfig.class,
        JwtAuthenticationFilter.class, RequestUserContext.class, JwtUtils.class})
class DocumentEndpointAuthenticationTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {
    }

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DocumentService documentService;

    @Test
    void anXUserIdHeaderCannotStandInForAuthentication() throws Exception {
        mockMvc.perform(get("/api/documents/doc-1").header("X-User-Id", "7"))
                .andExpect(status().isForbidden());

        verify(documentService, never()).getById(any(), anyLong());
    }

    @Test
    void aValidTokenResolvesTheUserAndAnXUserIdHeaderDoesNotOverrideIt() throws Exception {
        when(documentService.getById("doc-1", 42L)).thenReturn(new DocumentVO());
        String token = JwtUtils.generateToken(42L, "alice", "user");

        mockMvc.perform(get("/api/documents/doc-1")
                        .header("Authorization", "Bearer " + token)
                        .header("X-User-Id", "7"))
                .andExpect(status().isOk());

        verify(documentService).getById("doc-1", 42L);
        verify(documentService, never()).getById("doc-1", 7L);
    }

    @Test
    void anonymousRequestNeverReachesTheService() throws Exception {
        mockMvc.perform(get("/api/documents/doc-1"))
                .andExpect(status().isForbidden());

        verify(documentService, never()).getById(any(), anyLong());
    }
}
