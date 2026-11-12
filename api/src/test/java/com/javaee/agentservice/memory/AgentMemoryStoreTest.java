package com.javaee.agentservice.memory;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.javaee.common.exception.BusinessException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentMemoryStoreTest {

    private final AgentMemoryMapper memoryMapper = mock(AgentMemoryMapper.class);
    private final AgentMemoryStore store = new AgentMemoryStore();

    @BeforeEach
    void inject() {
        ReflectionTestUtils.setField(store, "memoryMapper", memoryMapper);
        // 列名缓存平时由 mapper 扫描填，纯单测里没人填，wrapper 渲染不出 SQL。
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                AgentMemory.class);
    }

    /** 同一件事只留一条：同名写入是覆盖，不是再插一条互相矛盾的。 */
    @Test
    void rememberOverwritesTheEntryOfTheSameName() {
        AgentMemory existing = memory("summary-length", "摘要一律 100 字内");
        existing.setId(3L);
        when(memoryMapper.selectOne(any())).thenReturn(existing);

        store.remember(memory("summary-length", "摘要一律 200 字内"));

        verify(memoryMapper).updateById(any(AgentMemory.class));
        verify(memoryMapper, never()).insert(any());
        assertThat(existing.getContent()).isEqualTo("摘要一律 200 字内");
    }

    @Test
    void rememberInsertsANewName() {
        when(memoryMapper.selectOne(any())).thenReturn(null);
        when(memoryMapper.selectCount(any())).thenReturn(0L);

        store.remember(memory("no-legal-edits", "legal 分类的文档不要改"));

        verify(memoryMapper).insert(any(AgentMemory.class));
    }

    /** 写满了报错，不悄悄淘汰——丢掉一条约束是安全问题。 */
    @Test
    void rememberRefusesOnceTheTypeIsFull() {
        when(memoryMapper.selectOne(any())).thenReturn(null);
        when(memoryMapper.selectCount(any()))
                .thenReturn((long) AgentMemoryStore.MAX_PER_TYPE);

        assertThatThrownBy(() -> store.remember(memory("x", "y")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("上限");
        verify(memoryMapper, never()).insert(any());
    }

    @Test
    void rememberRefusesContentBeyondTheLimit() {
        assertThatThrownBy(() -> store.remember(
                memory("x", "字".repeat(AgentMemoryStore.MAX_CONTENT_CHARS + 1))))
                .isInstanceOf(BusinessException.class);
        verify(memoryMapper, never()).insert(any());
    }

    /** 超出 prompt 预算时先丢 PROJECT，CONSTRAINT 一条都不能少。 */
    @Test
    void forRunDropsProjectBeforeConstraint() {
        String long1 = "字".repeat(400);
        when(memoryMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                typed("c1", MemoryType.CONSTRAINT, long1),
                typed("p1", MemoryType.PROJECT, long1),
                typed("r1", MemoryType.REFERENCE, long1),
                typed("f1", MemoryType.PREFERENCE, long1)
        ));

        Map<MemoryType, List<AgentMemory>> grouped = store.forRun(1L, "doc-1");

        assertThat(grouped).containsKey(MemoryType.CONSTRAINT);
    }

    /**
     * 没挂文档的 run 也要拼得出 SQL。条件挂在里层 eq 上时 or 照样拼出来，括号里
     * 是空的；mapper 在单测里是 mock 的，这种错只有真库会报。
     */
    @Test
    void forRunBuildsValidSql() {
        when(memoryMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        store.forRun(1L, null);
        store.forRun(1L, "doc-1");

        ArgumentCaptor<LambdaQueryWrapper<AgentMemory>> wrappers =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(memoryMapper, times(2)).selectList(wrappers.capture());
        assertThat(wrappers.getAllValues().get(0).getSqlSegment())
                .doesNotContain("OR )")
                .doesNotContain("document_id =");
        assertThat(wrappers.getAllValues().get(1).getSqlSegment())
                .doesNotContain("OR )")
                .contains("document_id =");
    }

    @Test
    void forgetReportsWhetherAnythingWasRemoved() {
        when(memoryMapper.selectOne(any())).thenReturn(null);
        assertThat(store.forget(1L, "nope")).isFalse();

        AgentMemory existing = memory("gone", "x");
        existing.setId(9L);
        when(memoryMapper.selectOne(any())).thenReturn(existing);
        when(memoryMapper.deleteById(9L)).thenReturn(1);
        assertThat(store.forget(1L, "gone")).isTrue();
    }

    private AgentMemory memory(String name, String content) {
        return typed(name, MemoryType.PREFERENCE, content);
    }

    private AgentMemory typed(String name, MemoryType type, String content) {
        AgentMemory memory = new AgentMemory();
        memory.setUserId(1L);
        memory.setName(name);
        memory.setType(type.name());
        memory.setContent(content);
        return memory;
    }
}
