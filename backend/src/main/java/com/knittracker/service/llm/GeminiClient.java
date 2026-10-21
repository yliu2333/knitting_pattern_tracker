package com.knittracker.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * Google Gemini streamGenerateContent (SSE) over REST. The API key goes in a header, never in the URL (URLs get logged).
 * Streaming, not generateContent: egress proxies drop requests with no response byte for ~30s, and a PDF parse thinks longer.
 */
public class GeminiClient implements LlmClient {

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final String apiKey;
    private final String thinkingLevel;

    /** @param thinkingLevel Gemini 3 thinking level (low/medium/high); blank = model default */
    public GeminiClient(HttpClient http, ObjectMapper mapper, String baseUrl, String model, String apiKey,
                        String thinkingLevel) {
        this.http = http;
        this.mapper = mapper;
        this.endpoint = URI.create(baseUrl + "/v1beta/models/" + model + ":streamGenerateContent?alt=sse");
        this.apiKey = apiKey;
        this.thinkingLevel = thinkingLevel;
    }

    @Override
    public String completeJson(String systemPrompt, String userPrompt, List<Attachment> attachments) {
        ObjectNode body = mapper.createObjectNode();
        body.putObject("systemInstruction").putArray("parts").addObject().put("text", systemPrompt);
        ArrayNode parts = body.putArray("contents").addObject().put("role", "user").putArray("parts");
        for (Attachment a : attachments) {
            parts.addObject().putObject("inline_data")
                    .put("mime_type", a.mimeType())
                    .put("data", Base64.getEncoder().encodeToString(a.data()));
        }
        parts.addObject().put("text", userPrompt);
        ObjectNode config = body.putObject("generationConfig")
                .put("responseMimeType", "application/json")
                .put("temperature", 0);
        if (thinkingLevel != null && !thinkingLevel.isBlank()) {
            config.putObject("thinkingConfig").put("thinkingLevel", thinkingLevel);
        }

        HttpRequest.Builder req = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofMinutes(3))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        // Blank key: an egress proxy adds the header (Claude cloud network secret).
        if (apiKey != null && !apiKey.isBlank()) req.header("x-goog-api-key", apiKey);
        try {
            HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                throw new LlmException("Gemini HTTP " + res.statusCode());
            }
            // One "data: {chunk}" line per event; the answer is the concatenated non-thought text parts.
            // Under load Gemini can answer 200, stream part of the answer, then send an error event:
            // a partial answer is an error, not a (truncated) result.
            StringBuilder text = new StringBuilder();
            String finish = null;
            for (String line : res.body().split("\\R")) {
                if (!line.startsWith("data:")) continue;
                JsonNode chunk = mapper.readTree(line.substring(5));
                if (chunk.has("error")) throw new LlmException("Gemini HTTP " + chunk.path("error").path("code").asInt());
                JsonNode candidate = chunk.path("candidates").path(0);
                candidate.path("content").path("parts").forEach(p -> {
                    if (!p.path("thought").asBoolean(false)) text.append(p.path("text").asText(""));
                });
                if (candidate.hasNonNull("finishReason")) finish = candidate.get("finishReason").asText();
            }
            if (!"STOP".equals(finish)) throw new LlmException("Gemini stream ended early: " + finish);
            if (text.isEmpty()) throw new LlmException("Gemini returned no text");
            return text.toString();
        } catch (IOException e) {
            throw new LlmException("Gemini call failed: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Gemini call interrupted");
        }
    }
}
