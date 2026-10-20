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

/** Google Gemini generateContent over REST. The API key goes in a header, never in the URL (URLs get logged). */
public class GeminiClient implements LlmClient {

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final String apiKey;

    public GeminiClient(HttpClient http, ObjectMapper mapper, String baseUrl, String model, String apiKey) {
        this.http = http;
        this.mapper = mapper;
        this.endpoint = URI.create(baseUrl + "/v1beta/models/" + model + ":generateContent");
        this.apiKey = apiKey;
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
        body.putObject("generationConfig")
                .put("responseMimeType", "application/json")
                .put("temperature", 0);

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
            JsonNode parts0 = mapper.readTree(res.body()).path("candidates").path(0).path("content").path("parts");
            StringBuilder text = new StringBuilder();
            parts0.forEach(p -> text.append(p.path("text").asText("")));
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
