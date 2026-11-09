package com.idraak.smartbite.v2.ai;

import java.time.Duration;

/** One AI provider/model behind an OpenAI-compatible chat completions API. */
public interface ChatModel {

    String name();

    /**
     * @return the assistant message content
     * @throws ProviderException on 429, 5xx, other HTTP errors, timeouts and connection errors
     */
    String complete(String systemPrompt, String userPrompt, Duration timeout, int maxTokens);

    /**
     * Like {@link #complete(String, String, Duration, int)}; {@code lowReasoning} asks a reasoning model to think
     * briefly (BE-030). Models that don't support it ignore it.
     */
    default String complete(String systemPrompt, String userPrompt, Duration timeout, int maxTokens, boolean lowReasoning) {
        return complete(systemPrompt, userPrompt, timeout, maxTokens);
    }
}
