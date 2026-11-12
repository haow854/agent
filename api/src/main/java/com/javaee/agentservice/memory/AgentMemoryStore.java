package com.javaee.agentservice.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.javaee.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** 用户记忆的读写。上限在这里把关，写满了报错而不是悄悄淘汰。 */
@Component
public class AgentMemoryStore {

    static final int MAX_PER_TYPE = 20;
    static final int MAX_CONTENT_CHARS = 500;
    static final int MAX_PROMPT_CHARS = 4000;
    private static final int MAX_NAME_CHARS = 64;
    private static final List<MemoryType> TRIM_ORDER =
            List.of(MemoryType.PROJECT, MemoryType.REFERENCE, MemoryType.PREFERENCE);

    @Autowired
    private AgentMemoryMapper memoryMapper;

    /**
     * 本次 run 该看到的：全局条目 + 属于这篇文档的条目，按 type 分组。
     *
     * <p>总量超 {@link #MAX_PROMPT_CHARS} 时按 PROJECT→REFERENCE→PREFERENCE 的顺序丢，
     * CONSTRAINT 永不丢：禁令被悄悄裁掉是安全问题。</p>
     */
    public Map<MemoryType, List<AgentMemory>> forRun(Long userId, String documentId) {
        // 条件挂在 or 上，不是挂在里面的 eq 上：挂在 eq 上时 or 照样会拼出来，
        // 括号里却是空的，出来的是 (document_id IS NULL OR )。
        boolean scoped = documentId != null && !documentId.isBlank();
        List<AgentMemory> all = memoryMapper.selectList(new LambdaQueryWrapper<AgentMemory>()
                .eq(AgentMemory::getUserId, userId)
                .and(q -> q.isNull(AgentMemory::getDocumentId)
                        .or(scoped, w -> w.eq(AgentMemory::getDocumentId, documentId)))
                .orderByAsc(AgentMemory::getId));

        Map<MemoryType, List<AgentMemory>> grouped = new EnumMap<>(MemoryType.class);
        for (AgentMemory memory : all) {
            grouped.computeIfAbsent(MemoryType.of(memory.getType()),
                    type -> new ArrayList<>()).add(memory);
        }
        trimToPromptBudget(grouped);
        return grouped;
    }

    private void trimToPromptBudget(Map<MemoryType, List<AgentMemory>> grouped) {
        for (MemoryType type : TRIM_ORDER) {
            if (renderedChars(grouped) <= MAX_PROMPT_CHARS) {
                return;
            }
            grouped.remove(type);
        }
    }

    private int renderedChars(Map<MemoryType, List<AgentMemory>> grouped) {
        return grouped.values().stream()
                .flatMap(List::stream)
                .mapToInt(memory -> memory.getName().length() + memory.getContent().length())
                .sum();
    }

    public List<AgentMemory> listAll(Long userId) {
        return memoryMapper.selectList(new LambdaQueryWrapper<AgentMemory>()
                .eq(AgentMemory::getUserId, userId)
                .orderByAsc(AgentMemory::getId));
    }

    /** name 已存在则覆盖，否则新增——同一件事只留一条，不留两条互相矛盾的。 */
    public AgentMemory remember(AgentMemory memory) {
        assertWritable(memory);
        AgentMemory existing = findByName(memory.getUserId(), memory.getName());
        if (existing != null) {
            existing.setType(memory.getType());
            existing.setContent(memory.getContent());
            existing.setDocumentId(memory.getDocumentId());
            existing.setSourceTaskId(memory.getSourceTaskId());
            memoryMapper.updateById(existing);
            return existing;
        }
        assertUnderTypeLimit(memory.getUserId(), memory.getType());
        memoryMapper.insert(memory);
        return memory;
    }

    /** @return 是否真删掉了一条 */
    public boolean forget(Long userId, String name) {
        AgentMemory existing = findByName(userId, name);
        if (existing == null) {
            return false;
        }
        return memoryMapper.deleteById(existing.getId()) > 0;
    }

    private AgentMemory findByName(Long userId, String name) {
        return memoryMapper.selectOne(new LambdaQueryWrapper<AgentMemory>()
                .eq(AgentMemory::getUserId, userId)
                .eq(AgentMemory::getName, name));
    }

    private void assertWritable(AgentMemory memory) {
        if (memory.getName() == null || memory.getName().isBlank()
                || memory.getName().length() > MAX_NAME_CHARS) {
            throw new BusinessException("记忆名称必须非空且不超过 " + MAX_NAME_CHARS + " 字符");
        }
        if (memory.getContent() == null || memory.getContent().isBlank()
                || memory.getContent().length() > MAX_CONTENT_CHARS) {
            throw new BusinessException("记忆内容必须非空且不超过 " + MAX_CONTENT_CHARS + " 字符");
        }
        MemoryType.of(memory.getType());
    }

    private void assertUnderTypeLimit(Long userId, String type) {
        Long count = memoryMapper.selectCount(new LambdaQueryWrapper<AgentMemory>()
                .eq(AgentMemory::getUserId, userId)
                .eq(AgentMemory::getType, type));
        if (count != null && count >= MAX_PER_TYPE) {
            throw new BusinessException(
                    "该类记忆已达 " + MAX_PER_TYPE + " 条上限，请先删除不再需要的条目");
        }
    }
}
