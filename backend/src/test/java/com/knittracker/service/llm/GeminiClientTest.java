package com.knittracker.service.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knittracker.service.llm.LlmClient.Attachment;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GeminiClientTest {

    final ObjectMapper mapper = new ObjectMapper();
    HttpServer server;
    final AtomicReference<String> path = new AtomicReference<>();
    final AtomicReference<String> key = new AtomicReference<>();
    final AtomicReference<JsonNode> body = new AtomicReference<>();
    volatile int status = 200;
    volatile String reply = """
            data: {"candidates":[{"content":{"parts":[{"text":"planning","thought":true}]}}]}

            data: {"candidates":[{"content":{"parts":[{"text":"{\\"a\\":"}]}}]}

            data: {"candidates":[{"content":{"parts":[{"text":"1}"}]},"finishReason":"STOP"}]}

            """;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            path.set(ex.getRequestURI().toString());
            key.set(ex.getRequestHeaders().getFirst("x-goog-api-key"));
            body.set(mapper.readTree(ex.getRequestBody()));
            byte[] out = reply.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    GeminiClient client() {
        return new GeminiClient(HttpClient.newHttpClient(), mapper,
                "http://127.0.0.1:" + server.getAddress().getPort(), "m-1", "SECRET", "low");
    }

    @Test
    void streamsAndJoinsNonThoughtTextWithKeyInHeaderOnly() {
        String out = client().completeJson("sys", "user", List.of(new Attachment("application/pdf", new byte[] {7})));

        assertThat(out).isEqualTo("{\"a\":1}");
        assertThat(path.get()).isEqualTo("/v1beta/models/m-1:streamGenerateContent?alt=sse").doesNotContain("SECRET");
        assertThat(key.get()).isEqualTo("SECRET");
        assertThat(body.get().at("/systemInstruction/parts/0/text").asText()).isEqualTo("sys");
        assertThat(body.get().at("/contents/0/parts/0/inline_data/mime_type").asText()).isEqualTo("application/pdf");
        assertThat(body.get().at("/contents/0/parts/0/inline_data/data").asText()).isEqualTo("Bw==");
        assertThat(body.get().at("/contents/0/parts/1/text").asText()).isEqualTo("user");
        assertThat(body.get().at("/generationConfig/responseMimeType").asText()).isEqualTo("application/json");
        assertThat(body.get().at("/generationConfig/thinkingConfig/thinkingLevel").asText()).isEqualTo("low");
    }

    @Test
    void blankKeySendsNoKeyHeaderSoAProxyCanInjectIt() {
        new GeminiClient(HttpClient.newHttpClient(), mapper,
                "http://127.0.0.1:" + server.getAddress().getPort(), "m-1", "", "").completeJson("s", "u", List.of());
        assertThat(key.get()).isNull();
        assertThat(body.get().at("/generationConfig/thinkingConfig").isMissingNode()).isTrue();
    }

    @Test
    void httpErrorsDoNotLeakTheResponseBody() {
        status = 429;
        reply = "{\"error\":\"quota for SECRET project\"}";
        assertThatThrownBy(() -> client().completeJson("s", "u", List.of()))
                .isInstanceOf(LlmException.class).hasMessage("Gemini HTTP 429");
    }

    @Test
    void anErrorEventInsideA200StreamFailsInsteadOfReturningThePartialAnswer() {
        reply = """
                data: {"candidates":[{"content":{"parts":[{"text":"{\\"a\\":"}]}}]}

                data: {"error":{"code":503,"message":"high demand for SECRET","status":"UNAVAILABLE"}}

                """;
        assertThatThrownBy(() -> client().completeJson("s", "u", List.of()))
                .isInstanceOf(LlmException.class).hasMessage("Gemini HTTP 503");
    }

    @Test
    void aStreamCutOffBeforeFinishReasonFails() {
        reply = """
                data: {"candidates":[{"content":{"parts":[{"text":"{\\"a\\":"}]}}]}

                """;
        assertThatThrownBy(() -> client().completeJson("s", "u", List.of()))
                .isInstanceOf(LlmException.class).hasMessage("Gemini stream ended early: null");
    }

    @Test
    void hittingTheOutputLimitFails() {
        reply = """
                data: {"candidates":[{"content":{"parts":[{"text":"{\\"a\\":"}]},"finishReason":"MAX_TOKENS"}]}

                """;
        assertThatThrownBy(() -> client().completeJson("s", "u", List.of()))
                .isInstanceOf(LlmException.class).hasMessage("Gemini stream ended early: MAX_TOKENS");
    }
}
