package com.javaee.agentservice.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionManagerTest {

    private SessionMapper sessionMapper;
    private SessionMessageMapper messageMapper;
    private SessionSummaryMapper summaryMapper;
    private SessionManager sessionManager;

    @BeforeEach
    void setUp() {
        sessionMapper = mock(SessionMapper.class);
        messageMapper = mock(SessionMessageMapper.class);
        summaryMapper = mock(SessionSummaryMapper.class);

        sessionManager = new SessionManager();
        ReflectionTestUtils.setField(sessionManager, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(sessionManager, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(sessionManager, "summaryMapper", summaryMapper);

        generatesId(7L);
        when(sessionMapper.updateLastMessage(anyString(), any(), any())).thenReturn(1);
    }

    /** The generated id is the cursor a later read starts from. */
    @Test
    void appendReturnsTheIdOfTheStoredMessage() {
        assertThat(sessionManager.appendUser("session-1", "task-1", "question"))
                .isEqualTo(7L);
    }

    /**
     * Zero rows updated means the session is gone, which is the check that used
     * to cost a round trip of its own before every append.
     */
    @Test
    void appendFailsWhenTheSessionIsGone() {
        when(sessionMapper.updateLastMessage(anyString(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> sessionManager.appendUser("session-1", "task-1", "q"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("session-1");
    }

    @Test
    void appendRecordsTheTaskThatProducedTheMessage() {
        sessionManager.appendUser("session-1", "task-9", "question");

        verify(sessionMapper).updateLastMessage("session-1", 7L, "task-9");
    }

    @Test
    void storesWhichToolAnsweredSoAssemblyNeedNotLookItUp() {
        sessionManager.appendTool("session-1", "task-1", "call-a", "document_read", "{}");

        verify(messageMapper).insert(argThatMessage(message -> {
            assertThat(message.getRole()).isEqualTo("tool");
            assertThat(message.getToolCallId()).isEqualTo("call-a");
            assertThat(message.getToolName()).isEqualTo("document_read");
        }));
    }

    /** The first question names the session, and only the first. */
    @Test
    void titlesANewSessionWithItsFirstQuestionOnOneLine() {
        sessionManager.ensureSession(null, 1L, null, "  帮我总结\n这份文档  ");

        verify(sessionMapper).insert(argThatSession(session ->
                assertThat(session.getTitle()).isEqualTo("帮我总结 这份文档")));
    }

    @Test
    void leavesTheTitleOfAnExistingSessionToTheDatabase() {
        when(sessionMapper.selectById("session-1")).thenReturn(session(1L));

        sessionManager.ensureSession("session-1", 1L, null, "后来的问题");

        verify(sessionMapper).setTitleIfAbsent("session-1", "后来的问题");
    }

    @Test
    void refusesASessionOwnedBySomeoneElse() {
        when(sessionMapper.selectById("session-1")).thenReturn(session(2L));

        assertThatThrownBy(() -> sessionManager.ensureSession("session-1", 1L, null, null))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void deletingASessionTakesItsMessagesAndFoldWithIt() {
        sessionManager.deleteSession("session-1");

        verify(messageMapper).delete(any());
        verify(summaryMapper).deleteById("session-1");
        verify(sessionMapper).deleteById(eq("session-1"));
    }

    private void generatesId(long id) {
        doAnswer(call -> {
            call.<SessionMessage>getArgument(0).setId(id);
            return 1;
        }).when(messageMapper).insert(any(SessionMessage.class));
    }

    private Session session(Long ownerId) {
        Session session = new Session();
        session.setId("session-1");
        session.setUserId(ownerId);
        return session;
    }

    private SessionMessage argThatMessage(java.util.function.Consumer<SessionMessage> check) {
        return org.mockito.ArgumentMatchers.argThat(message -> {
            check.accept(message);
            return true;
        });
    }

    private Session argThatSession(java.util.function.Consumer<Session> check) {
        return org.mockito.ArgumentMatchers.argThat(session -> {
            check.accept(session);
            return true;
        });
    }
}
