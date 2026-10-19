package com.knittracker.service.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LlmConfig {

    /** Without GEMINI_API_KEY the app still starts; parsing then fails with a clear error. */
    @Bean
    LlmClient llmClient(ObjectMapper mapper,
                        @Value("${app.llm.gemini.base-url}") String baseUrl,
                        @Value("${app.llm.gemini.model}") String model,
                        @Value("${app.llm.gemini.api-key}") String apiKey) {
        if (apiKey.isBlank()) {
            return (system, user, attachments) -> {
                throw new LlmException("GEMINI_API_KEY is not configured");
            };
        }
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
        return new GeminiClient(http, mapper, baseUrl, model, apiKey);
    }
}
