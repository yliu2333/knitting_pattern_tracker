package com.knittracker.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import com.fasterxml.jackson.databind.JsonNode;
import com.knittracker.ir.Fixtures;
import com.knittracker.service.parse.FakeLlm;
import java.nio.file.Files;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;

@Import(PatternUploadApiTest.FakeLlmConfig.class)
class PatternUploadApiTest extends ApiTestBase {

    @TestConfiguration
    static class FakeLlmConfig {
        @Bean
        @Primary
        FakeLlm fakeLlm() {
            return new FakeLlm();
        }
    }

    @Autowired FakeLlm llm;
    String hatJson;

    @BeforeEach
    void reset() throws Exception {
        llm.responses.clear();
        llm.calls.clear();
        hatJson = Files.readString(Fixtures.PUBLIC_DIR.resolve("sample-hat.ir.json"));
    }

    MockMultipartFile file(String content) {
        return new MockMultipartFile("file", "hat.pdf", "application/pdf", content.getBytes());
    }

    @Test
    void uploadParsesOnceAndCachesPerUser() throws Exception {
        String me = newUserToken();
        llm.reply(hatJson);
        JsonNode first = call(me, multipart("/api/patterns/upload").file(file("PDF-1")), 201);
        JsonNode again = call(me, multipart("/api/patterns/upload").file(file("PDF-1")), 200);
        assertThat(again.get("id")).isEqualTo(first.get("id"));
        assertThat(llm.calls).hasSize(1);

        // a different size of the same file is a different parse
        llm.reply(hatJson);
        call(me, multipart("/api/patterns/upload").file(file("PDF-1")).param("size", "L"), 201);
        assertThat(llm.calls).hasSize(2);

        // another user uploading the same bytes never gets the first user's parse
        String other = newUserToken();
        llm.reply(hatJson);
        JsonNode theirs = call(other, multipart("/api/patterns/upload").file(file("PDF-1")), 201);
        assertThat(theirs.get("id")).isNotEqualTo(first.get("id"));
        assertThat(llm.calls).hasSize(3);
    }

    @Test
    void parsedPatternIsTrackable() throws Exception {
        String me = newUserToken();
        llm.reply(hatJson);
        JsonNode up = call(me, multipart("/api/patterns/upload").file(file("PDF-2")), 201);
        JsonNode view = createProject(me, java.util.UUID.fromString(up.get("id").asText()));
        assertThat(view.get("totalSteps").asInt()).isEqualTo(7);
    }

    @Test
    void unparseableOutputIs422AndNothingIsStored() throws Exception {
        String me = newUserToken();
        llm.reply("{}", "{}", "{}");
        JsonNode res = call(me, multipart("/api/patterns/upload").file(file("PDF-3")), 422);
        assertThat(res.get("errors").toString()).contains("title is required");
        assertThat(call(me, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/patterns"), 200))
                .isEmpty();
    }

    @Test
    void unsupportedFileTypeIs400WithoutCallingTheLlm() throws Exception {
        call(newUserToken(), multipart("/api/patterns/upload")
                .file(new MockMultipartFile("file", "x.zip", "application/zip", new byte[] {1})), 400);
        assertThat(llm.calls).isEmpty();
    }

    @Test
    void uploadRequiresAuthentication() throws Exception {
        assertThat(mvc.perform(multipart("/api/patterns/upload").file(file("x"))).andReturn().getResponse().getStatus())
                .isEqualTo(401);
    }
}
