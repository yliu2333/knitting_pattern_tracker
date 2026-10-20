package com.knittracker.service.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LlmConfig {

    /**
     * Without GEMINI_API_KEY requests go out without a key header: in Claude cloud sessions an egress
     * proxy injects x-goog-api-key (network secret). Elsewhere Gemini answers 403 → parse fails with 422.
     */
    @Bean
    LlmClient llmClient(ObjectMapper mapper,
                        @Value("${app.llm.gemini.base-url}") String baseUrl,
                        @Value("${app.llm.gemini.model}") String model,
                        @Value("${app.llm.gemini.api-key}") String apiKey,
                        @Value("${app.llm.gemini.thinking-level}") String thinkingLevel) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
        return new GeminiClient(http, mapper, baseUrl, model, apiKey, thinkingLevel);
    }
}
