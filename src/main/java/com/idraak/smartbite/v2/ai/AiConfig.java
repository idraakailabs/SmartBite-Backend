package com.idraak.smartbite.v2.ai;

import com.idraak.smartbite.v2.config.V2Properties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/** v2 AI clients: the same provider order and models as v1, with v2's own timeouts. */
@Configuration
public class AiConfig {

    @Bean
    public ChatModels v2ChatModels(V2Properties props, ObjectMapper mapper) {
        V2Properties.Ai ai = props.ai();
        return new ChatModels(List.of(
                new OpenAiCompatibleChatModel("groq-gpt-oss-120b", "openai/gpt-oss-120b", ai.groqBaseUrl(), ai.groqApiKey(), true, ai.connectTimeout(), mapper),
                new OpenAiCompatibleChatModel("gemini-flash-lite", "gemini-2.5-flash-lite", ai.geminiBaseUrl(), ai.geminiApiKey(), false, ai.connectTimeout(), mapper),
                new OpenAiCompatibleChatModel("groq-gpt-oss-20b", "openai/gpt-oss-20b", ai.groqBaseUrl(), ai.groqApiKey(), true, ai.connectTimeout(), mapper)));
    }

    @Bean
    public ProviderChain suggestionProviderChain(ChatModels v2ChatModels, V2Properties props) {
        return new ProviderChain(v2ChatModels.all(), props.ai().readTimeout(), props.ai().minRemainingForAttempt());
    }

    @Bean
    public ProviderChain recipeProviderChain(ChatModels v2ChatModels, V2Properties props) {
        return new ProviderChain(v2ChatModels.all(), props.ai().recipeReadTimeout(), props.ai().minRemainingForAttempt());
    }
}
