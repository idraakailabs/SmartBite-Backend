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
}
