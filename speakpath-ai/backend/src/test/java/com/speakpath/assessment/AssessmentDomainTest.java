package com.speakpath.assessment;

import com.speakpath.exam.QuestionBankItem;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 自适应选题与状态机单元测试（无外部依赖）。
 */
class AssessmentDomainTest {

    @Test
    void stateMachineRejectsIllegalTransitions() throws Exception {
        Constructor<AssessmentSession> ctor = AssessmentSession.class.getDeclaredConstructor(
                UUID.class, UUID.class, String.class, int.class, int.class, java.math.BigDecimal.class, short.class);
        ctor.setAccessible(true);
        AssessmentSession session = ctor.newInstance(UUID.randomUUID(), UUID.randomUUID(),
                "IELTS_SPEAKING", 3, 3, new java.math.BigDecimal("7.0"), (short) 15);

        assertEquals(AssessmentSession.CREATED, session.getStatus());
        assertTrue(session.canTransition(AssessmentSession.IN_PROGRESS));
        assertFalse(session.canTransition(AssessmentSession.COMPLETED));
        session.transition(AssessmentSession.IN_PROGRESS);
        assertTrue(session.canTransition(AssessmentSession.PROCESSING));
        session.transition(AssessmentSession.PROCESSING);
        assertTrue(session.canTransition(AssessmentSession.COMPLETED));
        assertThrows(IllegalStateException.class, () -> session.transition(AssessmentSession.IN_PROGRESS));
    }

    @Test
    void selectorCoversSectionsAndExcludesUsedQuestions() throws Exception {
        AdaptiveQuestionSelector selector = new AdaptiveQuestionSelector();
        List<QuestionBankItem> pool = List.of(item("PART_1", (short) 2), item("PART_1", (short) 5),
                item("PART_2", (short) 3), item("PART_2", (short) 7), item("PART_3", (short) 4));
        List<String> order = selector.sectionOrder(pool);
        assertEquals(List.of("PART_1", "PART_2", "PART_3"), order);

        Set<UUID> used = new HashSet<>();
        UUID first = selector.selectNext(pool, used, 1, order).getId();
        used.add(first);
        // 轮转：sequence 2 -> PART_2, sequence 3 -> PART_3, sequence 4 -> 回到 PART_1
        UUID second = selector.selectNext(pool, used, 2, order).getId();
        assertEquals("PART_2", pool.stream().filter(q -> q.getId().equals(second)).findFirst().orElseThrow().getSectionCode());
        used.add(second);
        UUID third = selector.selectNext(pool, used, 3, order).getId();
        assertEquals("PART_3", pool.stream().filter(q -> q.getId().equals(third)).findFirst().orElseThrow().getSectionCode());
        used.add(third);
        UUID fourth = selector.selectNext(pool, used, 4, order).getId();
        assertEquals("PART_1", pool.stream().filter(q -> q.getId().equals(fourth)).findFirst().orElseThrow().getSectionCode());
    }

    private QuestionBankItem item(String section, short difficulty) throws Exception {
        Constructor<QuestionBankItem> ctor = QuestionBankItem.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        QuestionBankItem item = ctor.newInstance();
        // 通过反射写入私有字段（种子数据在 SQL 中，测试避免依赖 DB）
        var sectionField = QuestionBankItem.class.getDeclaredField("sectionCode");
        sectionField.setAccessible(true);
        sectionField.set(item, section);
        var difficultyField = QuestionBankItem.class.getDeclaredField("difficulty");
        difficultyField.setAccessible(true);
        difficultyField.set(item, difficulty);
        var idField = QuestionBankItem.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(item, UUID.randomUUID());
        return item;
    }
}
