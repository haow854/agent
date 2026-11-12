package com.javaee.agentservice.session;

import com.javaee.agentservice.model.AgentState;
import com.javaee.agentservice.llm.LlmClient;
import com.javaee.agentservice.prompt.PromptEngineeringService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Folds the turns before the current task into one summary.
 *
 * <p>Runs when the chain stops fitting, and folds only what is safe to lose the
 * wording of: the current task is left whole, because it is the reasoning in
 * progress and a summary of half an argument is not an argument.</p>
 *
 * <p>What the user said is copied, not summarised. Their words are the only
 * record of what was actually asked, and a model asked to compress them will
 * paraphrase away a constraint sooner than it will drop its own reasoning. The
 * same goes for the documents that were touched: those are read off the tool
 * calls rather than inferred from prose, so they survive whatever the summary
 * turns out like.</p>
 */
@Component
public class AutoCompactor {

    private static final Logger log = LoggerFactory.getLogger(AutoCompactor.class);
    private static final Pattern DOCUMENT_ID =
            Pattern.compile("\"documentId\"\\s*:\\s*\"([^\"]+)\"");

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private PromptEngineeringService promptEngineeringService;

    @Autowired
    private SessionSummaryMapper summaryMapper;

    @Autowired
    private LlmClient modelClient;

    /** @return false 表示已经折不动了，这个 run 只能停 */
    public boolean compact(AgentState state) {
        SessionSummary existing = summaryMapper.selectById(state.getSessionId());
        List<SessionMessage> foldable = new ArrayList<>();
        for (SessionMessage message : sessionManager.getHistoryFrom(
                state.getSessionId(),
                existing == null ? null : existing.getCoveredTo() + 1)) {
            if (state.getTaskId().equals(message.getTaskId())) {
                break;
            }
            foldable.add(message);
        }
        if (foldable.isEmpty()) {
            return false;
        }

        Long coveredTo = foldable.get(foldable.size() - 1).getId();
        SessionSummary summary = new SessionSummary();
        summary.setSessionId(state.getSessionId());
        summary.setSummaryText(summarise(state, existing, foldable));
        summary.setCoveredTo(coveredTo);
        summaryMapper.upsert(summary);
        log.info("Compacted session {} up to {}: {} entries folded",
                state.getSessionId(), coveredTo, foldable.size());
        return true;
    }

    private String summarise(AgentState state,
                             SessionSummary existing,
                             List<SessionMessage> foldable) {
        String kept = verbatim(existing, foldable);
        try {
            String written = modelClient.completeText(state.getConfig().model(),
                    promptEngineeringService.createCompactionPrompt(transcript(foldable)));
            return written.isBlank() ? kept : kept + "\n\n【经过】\n" + written.trim();
        } catch (Exception e) {
            // The parts that matter most need no model, so a model that is down
            // costs detail rather than the fold.
            log.warn("Falling back to extraction for session {}: {}",
                    state.getSessionId(), e.getMessage());
            return kept;
        }
    }

    /**
     * The part of the fold that is copied rather than written: what the user
     * asked, and which documents were touched.
     */
    private String verbatim(SessionSummary existing, List<SessionMessage> foldable) {
        StringBuilder text = new StringBuilder("以下是更早对话的摘要。\n");
        if (existing != null) {
            text.append('\n').append(existing.getSummaryText().trim()).append('\n');
        }

        List<String> questions = new ArrayList<>();
        Set<String> documentIds = new LinkedHashSet<>();
        for (SessionMessage message : foldable) {
            if ("user".equals(message.getRole()) && message.getContent() != null
                    && !message.getContent().isBlank()) {
                questions.add(message.getContent().trim());
            }
            List<ToolCall> calls = message.getToolCalls();
            if (calls == null) {
                continue;
            }
            for (ToolCall call : calls) {
                Matcher matcher = DOCUMENT_ID.matcher(
                        call.arguments() == null ? "" : call.arguments());
                while (matcher.find()) {
                    documentIds.add(matcher.group(1));
                }
            }
        }

        if (!questions.isEmpty()) {
            text.append("\n【用户提出过】\n");
            questions.forEach(question -> text.append("- ").append(question).append('\n'));
        }
        if (!documentIds.isEmpty()) {
            text.append("\n【涉及的文档】\n");
            documentIds.forEach(id -> text.append("- ").append(id).append('\n'));
        }
        return text.toString();
    }

    private String transcript(List<SessionMessage> foldable) {
        StringBuilder text = new StringBuilder();
        for (SessionMessage message : foldable) {
            String content = message.getContent() == null ? "" : message.getContent().trim();
            if (content.isEmpty()) {
                continue;
            }
            text.append(message.getRole()).append(": ").append(content).append('\n');
        }
        return text.toString();
    }
}
