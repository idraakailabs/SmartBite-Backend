package com.idraak.smartbite.v2.ai;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * POST {baseUrl}/chat/completions with a per-call timeout. {@code jsonMode} adds
 * {@code response_format: {"type":"json_object"}} (enabled for Groq; off for Gemini's OpenAI-compatible endpoint
 * until verified, where the prompt asks for JSON only).
 */
public class OpenAiCompatibleChatModel implements ChatModel {

    private final String name;
    private final String model;
    private final URI endpoint;
    private final String apiKey;
    private final boolean jsonMode;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public OpenAiCompatibleChatModel(String name, String model, String baseUrl, String apiKey, boolean jsonMode,
                                     Duration connectTimeout, ObjectMapper mapper) {
        this.name = name;
        this.model = model;
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/chat/completions");
        this.apiKey = apiKey == null ? "" : apiKey;
        this.jsonMode = jsonMode;
        this.http = HttpClient.newBuilder().connectTimeout(connectTimeout).version(HttpClient.Version.HTTP_1_1).build();
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String complete(String systemPrompt, String userPrompt, Duration timeout, int maxTokens) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt);
        messages.addObject().put("role", "user").put("content", userPrompt);
        body.put("temperature", 0.7);
        body.put("max_tokens", maxTokens);
        if (jsonMode) body.putObject("response_format").put("type", "json_object");

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw new ProviderException(ProviderException.Kind.TIMEOUT, name + " timed out", e);
        } catch (ConnectException e) {
            throw new ProviderException(ProviderException.Kind.CONNECTION, name + " connection failed", e);
        } catch (IOException e) {
            throw new ProviderException(ProviderException.Kind.CONNECTION, name + " I/O error", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException(ProviderException.Kind.CONNECTION, name + " interrupted", e);
        }
        int status = response.statusCode();
        if (status == 429) throw new ProviderException(ProviderException.Kind.RATE_LIMITED, name + " 429", null);
        if (status >= 500) throw new ProviderException(ProviderException.Kind.SERVER_ERROR, name + " " + status, null);
        if (status >= 400) throw new ProviderException(ProviderException.Kind.CLIENT_ERROR, name + " " + status, null);
        try {
            JsonNode json = mapper.readTree(response.body());
            JsonNode content = json.path("choices").path(0).path("message").path("content");
            if (!content.isString()) throw new ProviderException(ProviderException.Kind.SERVER_ERROR, name + " empty content", null);
            return content.asString();
        } catch (RuntimeException e) {
            if (e instanceof ProviderException pe) throw pe;
            throw new ProviderException(ProviderException.Kind.SERVER_ERROR, name + " unreadable response", e);
        }
    }
}
