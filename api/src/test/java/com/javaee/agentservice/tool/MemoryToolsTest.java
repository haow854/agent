package com.javaee.agentservice.tool;

import com.javaee.agentservice.memory.AgentMemory;
import com.javaee.agentservice.memory.AgentMemoryStore;
import com.javaee.agentservice.memory.MemoryType;
import com.javaee.agentservice.model.AgentState;
import com.javaee.agentservice.model.AgentToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryToolsTest {

    private final AgentMemoryStore memoryStore = mock(AgentMemoryStore.class);
    private final RememberTool remember = new RememberTool();
    private final ForgetTool forget = new ForgetTool();

    @BeforeEach
    void inject() {
        ReflectionTestUtils.setField(remember, "memoryStore", memoryStore);
        ReflectionTestUtils.setField(forget, "memoryStore", memoryStore);
    }

    /** 记忆属于发起 run 的用户，不属于会话——这是它能跨会话的前提。 */
    @Test
    void remembersAgainstTheUserWhoStartedTheRun() {
        when(memoryStore.remember(any())).thenAnswer(call -> call.getArgument(0));

        AgentToolResult result = remember.execute("task-1:call-1", Map.of(
                "name", "summary-length",
                "type", "preference",
                "content", "摘要一律控制在 100 字内"), state());

        assertThat(result.getStatus()).isEqualTo("success");
        ArgumentCaptor<AgentMemory> saved = ArgumentCaptor.forClass(AgentMemory.class);
        verify(memoryStore).remember(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(7L);
        assertThat(saved.getValue().getType()).isEqualTo(MemoryType.PREFERENCE.name());
        assertThat(saved.getValue().getSourceTaskId()).isEqualTo("task-1");
    }

    @Test
    void refusesAnUnknownType() {
        assertThatThrownBy(() -> remember.execute("task-1:call-1", Map.of(
                "name", "x", "type", "whatever", "content", "y"), state()))
                .isInstanceOf(IllegalArgumentException.class);
        verify(memoryStore, never()).remember(any());
    }

    /** 删一条不存在的记忆不是错误，如实回报即可。 */
    @Test
    void forgetReportsWhenThereWasNothingToRemove() {
        when(memoryStore.forget(anyLong(), anyString())).thenReturn(false);

        AgentToolResult result = forget.execute(
                "task-1:call-1", Map.of("name", "nope"), state());

        assertThat(result.getStatus()).isEqualTo("success");
        assertThat(result.getData()).containsEntry("removed", false);
    }

    @Test
    void neitherToolNeedsApproval() {
        assertThat(remember.definition().isRequiresApproval()).isFalse();
        assertThat(forget.definition().isRequiresApproval()).isFalse();
    }

    private AgentState state() {
        return AgentState.create("task-1", "session-1", 7L,
                new AgentState.RunConfig("goal", "test-model", "system prompt",
                        List.of("remember", "forget"), null, "all", "<workspace>", "", 6, 8),
                0L);
    }
}
