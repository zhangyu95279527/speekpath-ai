package com.speakpath.assessment;

import com.speakpath.exam.QuestionBankItem;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 自适应选题（规格书 §8.2）：
 * candidateScore(q) = w1*coverageGain + w2*uncertaintyReduction + w3*examRelevance + w4*difficultyFit - w5*repetitionPenalty
 * 简化实现：section 轮转保证覆盖 + 难度贴合 + 已选题排除。
 */
@Component
public class AdaptiveQuestionSelector {

    /**
     * @param usedQuestionIds 已用题目
     * @param sectionOrder    期望的 section 顺序（轮转）
     */
    public QuestionBankItem selectNext(List<QuestionBankItem> pool, Set<UUID> usedQuestionIds,
                                       int nextSequence, List<String> sectionOrder) {
        List<QuestionBankItem> candidates = pool.stream()
                .filter(q -> !usedQuestionIds.contains(q.getId()))
                .toList();
        if (candidates.isEmpty()) {
            candidates = pool; // 题目耗尽则允许复用（生产按题库规模不应出现）
        }
        String preferredSection = sectionOrder.get(Math.floorMod(nextSequence - 1, sectionOrder.size()));
        List<QuestionBankItem> inSection = candidates.stream()
                .filter(q -> q.getSectionCode().equals(preferredSection)).toList();
        List<QuestionBankItem> target = inSection.isEmpty() ? candidates : inSection;

        // 难度贴合：中位难度优先（5），已用题目平均难度保持稳定
        short idealDifficulty = 5;
        return target.stream()
                .min((a, b) -> {
                    int da = Math.abs(a.getDifficulty() - idealDifficulty);
                    int db = Math.abs(b.getDifficulty() - idealDifficulty);
                    return da != db ? Integer.compare(da, db) : Integer.compare(a.getDifficulty(), b.getDifficulty());
                })
                .orElseThrow(() -> new IllegalStateException("Question pool is empty"));
    }

    public List<String> sectionOrder(List<QuestionBankItem> pool) {
        Map<String, Integer> order = new HashMap<>();
        List<String> sections = new ArrayList<>();
        for (QuestionBankItem q : pool) {
            if (!order.containsKey(q.getSectionCode())) {
                order.put(q.getSectionCode(), order.size());
                sections.add(q.getSectionCode());
            }
        }
        return sections;
    }
}
