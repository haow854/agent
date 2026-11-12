package com.javaee.agentservice.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.agentservice.model.AgentExecutionRequest;
import com.javaee.agentservice.model.AgentState;
import com.javaee.agentservice.model.AgentTaskResponse;
import com.javaee.agentservice.orchestration.AgentExecutionService;
import com.javaee.agentservice.tool.AgentToolRegistry;
import com.javaee.agentservice.prompt.PromptEngineeringService;
import com.javaee.documentservice.dto.DocumentCreateDTO;
import com.javaee.documentservice.dto.DocumentUpdateDTO;
import com.javaee.documentservice.service.DocumentService;
import com.javaee.documentservice.vo.DocumentVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

/**
 * Measures what a request actually costs, so the compaction settings are chosen
 * from numbers rather than from estimates of them.
 *
 * <p>Two questions, and only the second one needs a model. What a request costs
 * before any conversation happens — prompt, tool schemas, background — is a
 * property of the code and is measured here directly. How fast a session grows
 * on top of that is a property of what the model decides to do, so it is
 * measured by running real tasks and reading the per-turn line
 * {@code ReactRunner} logs.</p>
 *
 * <p>Rabbit listeners are off: this shares Redis and MySQL with whatever else is
 * running locally, and a probe that consumed a queued job would be running
 * someone else's work.</p>
 */
@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
class ContextSizingProbe {

    private static final Logger log = LoggerFactory.getLogger(ContextSizingProbe.class);
    private static final Long USER = 1L;

    @Autowired
    private ContextManager contextManager;
    @Autowired
    private SessionManager sessionManager;
    @Autowired
    private AgentToolRegistry toolRegistry;
    @Autowired
    private DocumentService documentService;
    @Autowired
    private AgentExecutionService executionService;
    @Autowired
    private PromptEngineeringService promptEngineeringService;
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * The part of every request that no compaction can remove.
     *
     * <p>This is the floor any threshold has to clear, and it is also the part
     * {@code estimateTokens} cannot see: the tool schemas are sent beside the
     * chain, not in it, so the estimate is short by their whole size on every
     * single turn.</p>
     */
    @Test
    void reportsWhatOneRequestCostsBeforeAnythingIsSaid() throws Exception {
        String systemPrompt = promptEngineeringService.agentSystemPrompt();
        String toolSchemas = objectMapper.writeValueAsString(toolRegistry.modelTools());

        String sessionId = sessionManager.createSession(USER, null);
        try {
            AgentState state = AgentState.create("probe-floor", sessionId, USER,
                    new AgentState.RunConfig("总结这篇文档", "deepseek-v4-pro",
                            systemPrompt, toolRegistry.selectableToolNames(),
                            null, "all", "<workspace>", "", 20, 40),
                    System.currentTimeMillis());
            sessionManager.appendUser(sessionId, "probe-floor", "总结这篇文档");
            List<Message> chain = contextManager.buildMessages(state);

            log.info("FLOOR system={} chars, toolSchemas={} chars ({} tools), "
                            + "chain={} chars, estimate={} tokens",
                    systemPrompt.length(),
                    toolSchemas.length(),
                    toolRegistry.modelTools().size(),
                    chain.stream().mapToInt(m -> m.getContent() == null
                            ? 0 : m.getContent().length()).sum(),
                    contextManager.estimateTokens(chain));
            for (Message message : chain) {
                log.info("FLOOR   {} {} chars", message.getMessageType(),
                        message.getContent() == null ? 0 : message.getContent().length());
            }
        } finally {
            sessionManager.deleteSession(sessionId);
        }
    }

    /**
     * How a real session grows. Reads the numbers off {@code ReactRunner}'s
     * per-turn log line; this only supplies documents and tasks worth measuring.
     *
     * <p>All four tasks share one session, because that is where the growth
     * being measured happens. A budget high enough not to bind is deliberate:
     * the run has to be allowed to get as large as it naturally would, or the
     * measurement is of the budget rather than of the workload.</p>
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_LIVE_MODEL_TESTS", matches = "true")
    void reportsHowFastASessionGrowsOverRealTasks() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        String.valueOf(USER), null,
                        List.of(new SimpleGrantedAuthority("user"))));

        String medium = seed("探针-中文档", 15_000);
        String large = seed("探针-长文档", 60_000);
        String sessionId = sessionManager.createSession(USER, null);

        run(sessionId, medium, "总结这篇文档的主要内容");
        run(sessionId, medium, "把第 2 节改写得更简洁一些");
        run(sessionId, large, "这篇文档讲了哪些议题？逐条列出来");
        run(sessionId, large, "刚才那些议题里，哪一个和前一篇文档重合？");

        log.info("PROBE session {} finished; documents medium={} large={}",
                sessionId, medium, large);
    }

    private void run(String sessionId, String documentId, String task) {
        AgentExecutionRequest request = new AgentExecutionRequest();
        request.setSessionId(sessionId);
        request.setDocumentId(documentId);
        request.setTask(task);
        // Pinned at the enqueue boundary in production; a direct caller that
        // leaves it blank gets one task id for every submission.
        request.setIdempotencyKey(UUID.randomUUID().toString());
        request.setModel("deepseek-v4-pro");

        AgentTaskResponse response = executionService.execute(request);
        log.info("PROBE task \"{}\" -> {} ({})",
                task, response.getStatus(), response.getTaskId());
    }

    /** A document of about {@code chars} characters, stored the way any other is. */
    private String seed(String title, int chars) {
        DocumentCreateDTO create = new DocumentCreateDTO();
        create.setTitle(title);
        DocumentVO document = documentService.create(create, USER);

        StringBuilder html = new StringBuilder();
        for (int section = 1; html.length() < chars; section++) {
            html.append("<h2>第 ").append(section).append(" 节</h2><p>");
            for (int sentence = 0; sentence < 12; sentence++) {
                html.append("这一节讨论第 ").append(section)
                        .append(" 个议题的第 ").append(sentence)
                        .append(" 个方面，包括它的背景、当前的做法、已知的问题，"
                                + "以及下一步可能的改动。");
            }
            html.append("</p>");
        }

        DocumentUpdateDTO update = new DocumentUpdateDTO();
        update.setContent(html.toString());
        update.setChangeLog("probe seed");
        documentService.update(document.getId(), update, USER);
        log.info("PROBE seeded {} = {} ({} chars)", title, document.getId(), html.length());
        return document.getId();
    }
}
