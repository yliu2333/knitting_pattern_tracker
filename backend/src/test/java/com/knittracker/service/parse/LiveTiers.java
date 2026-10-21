package com.knittracker.service.parse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knittracker.service.llm.GeminiClient;
import com.knittracker.service.llm.LlmTiers;
import java.net.http.HttpClient;
import java.util.Arrays;

/** Live tests use the same model chain as the app: GEMINI_MODELS (cheapest first), key from env or proxy. */
final class LiveTiers {

    static final String DEFAULT = "gemini-3.1-flash-lite,gemini-3.8-flash";

    private LiveTiers() {}

    static LlmTiers fromEnv(ObjectMapper mapper) {
        HttpClient http = HttpClient.newHttpClient();
        String key = System.getenv().getOrDefault("GEMINI_API_KEY", "");
        String thinking = System.getenv().getOrDefault("GEMINI_THINKING_LEVEL", "");
        return new LlmTiers(Arrays.stream(System.getenv().getOrDefault("GEMINI_MODELS", DEFAULT).split(","))
                .map(String::strip).filter(m -> !m.isEmpty())
                .map(m -> new LlmTiers.Tier(m, new GeminiClient(http, mapper,
                        "https://generativelanguage.googleapis.com", m, key, thinking)))
                .toList());
    }
}
