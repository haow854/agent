package com.javaee.agentservice.controller;

import com.javaee.agentservice.model.AgentExecutionRequest;
import com.javaee.agentservice.model.AgentTaskResponse;
import com.javaee.agentservice.orchestration.AgentExecutionService;
import com.javaee.agentservice.session.SessionManager;
import com.javaee.common.security.RequestUserContext;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentControllerTest {

    private MockMvc mockMvc;
    private AgentExecutionService agentExecutionService;
    private RequestUserContext requestUserContext;
    private SessionManager sessionManager;

    @BeforeEach
    void setUp() {
        AgentController controller = new AgentController();
        agentExecutionService = mock(AgentExecutionService.class);
        requestUserContext = mock(RequestUserContext.class);
        sessionManager = mock(SessionManager.class);
        when(requestUserContext.getRequiredUserId()).thenReturn(1L);
        when(requestUserContext.getCurrentRole()).thenReturn("user");
        when(requestUserContext.isAdmin()).thenReturn(false);

        ReflectionTestUtils.setField(controller, "agentExecutionService", agentExecutionService);
        ReflectionTestUtils.setField(controller, "requestUserContext", requestUserContext);
        ReflectionTestUtils.setField(controller, "sessionManager", sessionManager);

        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void listTasksReturnsCurrentUserSnapshots() throws Exception {
        when(agentExecutionService.listTasks(1L))
                .thenReturn(List.of(taskResponse("task-1", "success")));

        mockMvc.perform(get("/api/ai/agent/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].taskId").value("task-1"));
    }

    /**
     * Ownership moved into the service, which is the only place that can check
     * it now that the controller no longer loads the task itself.
     */
    @Test
    void cancelTaskSurfacesTheServicesOwnershipRefusal() throws Exception {
        when(agentExecutionService.cancelTask("task-2"))
                .thenThrow(new SecurityException("No permission to access this Agent task"));

        assertThatThrownBy(() -> mockMvc.perform(post("/api/ai/agent/tasks/task-2/cancel")))
                .hasRootCauseInstanceOf(SecurityException.class);
    }

    @Test
    void doesNotPublishARecoverEndpoint() throws Exception {
        mockMvc.perform(post("/api/ai/agent/tasks/task-1/recover"))
                .andExpect(status().isNotFound());
    }

    @Test
    void workbenchOverviewIncludesToolsSessionsAndTasks() throws Exception {
        when(agentExecutionService.listTools()).thenReturn(List.of());
        when(sessionManager.getUserSessions(1L)).thenReturn(List.of());
        when(agentExecutionService.listTasks(1L))
                .thenReturn(List.of(taskResponse("task-1", "success")));
        mockMvc.perform(get("/api/ai/agent/workbench/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tasks", hasSize(1)))
                .andExpect(jsonPath("$.data.tools", hasSize(0)))
                .andExpect(jsonPath("$.data.sessions", hasSize(0)))
                .andExpect(jsonPath("$.data.user.userId").value(1));
    }

    private AgentTaskResponse taskResponse(
            String taskId, String status) {
        AgentTaskResponse response = new AgentTaskResponse();
        response.setTaskId(taskId);
        response.setStatus(status);
        return response;
    }
}
