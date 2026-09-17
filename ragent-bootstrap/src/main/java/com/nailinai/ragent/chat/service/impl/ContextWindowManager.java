package com.nailinai.ragent.chat.service.impl;

import com.nailinai.ragent.entity.ChatMessage;
import com.nailinai.ragent.framework.util.TokenEstimateUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 对话上下文窗口管理。
 *
 * <p>多轮对话中，历史消息既要保证连续（至少保留最近几轮），
 * 又要控制 token 预算（避免超出模型上下文窗口）。策略：
 * <ol>
 *   <li>优先满足"至少保留最近 {@code reserveLatestCount} 条"的连续性下限；</li>
 *   <li>在预算允许的前提下尽量多保留历史；</li>
 *   <li>超出预算时从最旧消息开始丢弃，直到满足 token 预算。</li>
 * </ol>
 *
 * <p>相比"按条数硬截断"，该策略在长短消息混合时更合理：短消息可以多留几轮，
 * 长消息会被优先裁剪。后续可在此扩展摘要压缩（对早期消息生成一句话摘要）。
 */
@Component
public class ContextWindowManager {

    private final int maxHistoryTokens;

    public ContextWindowManager(@Value("${app.rag.history-token-budget:6000}") int maxHistoryTokens) {
        this.maxHistoryTokens = Math.max(100, maxHistoryTokens);
    }

    public int getMaxHistoryTokens() {
        return maxHistoryTokens;
    }

    /**
     * 在消息条数限制基础上，进一步按 token 预算裁剪历史。
     *
     * @param history             原始历史消息（按时间升序）
     * @param reserveLatestCount  至少保留的最近消息条数（>=1）
     * @return 裁剪后的历史消息（不可变列表）
     */
    public List<ChatMessage> trimHistory(List<ChatMessage> history, int reserveLatestCount) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        int reserve = Math.max(1, reserveLatestCount);
        if (history.size() <= reserve) {
            return List.copyOf(history);
        }

        int totalTokens = history.stream()
                .mapToInt(message -> TokenEstimateUtils.estimate(message.getContent()))
                .sum();
        if (totalTokens <= maxHistoryTokens) {
            return List.copyOf(history);
        }

        List<ChatMessage> window = new ArrayList<>(history);
        while (window.size() > reserve && totalTokens > maxHistoryTokens) {
            totalTokens -= TokenEstimateUtils.estimate(window.remove(0).getContent());
        }
        return List.copyOf(window);
    }
}
