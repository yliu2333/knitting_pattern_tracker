package com.knittracker.service.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LlmConfig {

    /**
     * One GeminiClient per model in GEMINI_MODELS (cheapest first). Without GEMINI_API_KEY requests go out
     * without a key header: in Claude cloud sessions an egress proxy injects x-goog-api-key (network secret).
     */
    @Bean
    LlmTiers llmTiers(ObjectMapper mapper,
                      @Value("${app.llm.gemini.base-url}") String baseUrl,
                      @Value("${app.llm.gemini.models}") List<String> models,
                      @Value("${app.llm.gemini.api-key}") String apiKey,
                      @Value("${app.llm.gemini.thinking-level}") String thinkingLevel) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
        return new LlmTiers(models.stream().map(String::strip).filter(m -> !m.isEmpty())
                .map(m -> new LlmTiers.Tier(m, new GeminiClient(http, mapper, baseUrl, m, apiKey, thinkingLevel)))
                .toList());
    }
}
