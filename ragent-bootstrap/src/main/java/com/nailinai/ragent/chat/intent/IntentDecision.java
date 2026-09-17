package com.nailinai.ragent.chat.intent;

public record IntentDecision(
        Intent intent,
        String clarification
) {
    public static IntentDecision kb() {
        return new IntentDecision(Intent.KB, null);
    }

    public static IntentDecision mcp() {
        return new IntentDecision(Intent.MCP, null);
    }

    public static IntentDecision system() {
        return new IntentDecision(Intent.SYSTEM, null);
    }

    public static IntentDecision clarify(String question) {
        return new IntentDecision(Intent.CLARIFY, question);
    }
}