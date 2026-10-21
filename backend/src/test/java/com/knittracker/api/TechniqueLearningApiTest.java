package com.knittracker.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import com.fasterxml.jackson.databind.JsonNode;
import com.knittracker.security.UserScope;
import com.knittracker.service.llm.LlmTiers;
import com.knittracker.service.parse.FakeLlm;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;

/** Learning end to end: a pattern teaches a technique, it stays private, and is promoted once two users agree. */
@Import(TechniqueLearningApiTest.FakeLlmConfig.class)
class TechniqueLearningApiTest extends ApiTestBase {

    @TestConfiguration
    static class FakeLlmConfig {
        @Bean
        @Primary
        FakeLlm fakeLlm() {
            return new FakeLlm();
        }

        @Bean
        @Primary
        LlmTiers fakeTiers(FakeLlm fake) {
            return LlmTiers.single("fake", fake);
        }
    }

    @Autowired FakeLlm llm;
    @Autowired UserScope scope;
    @Autowired JdbcClient jdbc;

    /** Unique term per test run, so promotion state from other tests can't interfere. */
    String term;
    String code;

    @BeforeEach
    void reset() {
        llm.responses.clear();
        llm.calls.clear();
        String id = UUID.randomUUID().toString().substring(0, 6);
        term = "kfbx" + id;
        code = "KFBX_" + id.toUpperCase().replace('-', '_');
    }

    /** 10 sts, one round of (term) to end → 20 sts, as the pattern states. */
    String parsed() {
        return """
                {"irVersion":1,"title":"Mini","craft":"KNIT","sourceType":"TEXT_PDF","sourceLang":"en","sections":[{"nameSrc":"s","steps":[
                 {"kind":"CHECKPOINT","titleSrc":"co","rows":[{"segments":[{"code":"CO","count":10},{"code":"PM","marker":"BOR"},{"code":"JOIN_ROUND"}]}]},
                 {"kind":"ROWS","unit":"ROUND","titleSrc":"inc","total":1,"statedEndSts":20,"rows":[{"segments":[{"code":"REPEAT","until":"END","children":[
                   {"code":"UNKNOWN","raw":"%s","attrs":{"context":"(%s) to end. 20 sts."}}]}]}]}]}]}
                """.formatted(term, term);
    }

    String defined() {
        return """
                {"terms":[{"term":"%s","technique":{"code":"%s","kind":"STITCH",
                  "def":{"consumes":1,"produces":2,"worked":"KNIT","source":"LIVE","method":"%s"},
                  "names":{"zh":"前后加针%s","en":"knit front and back %s"},
                  "templates":{"zh":"前后加针","en":"kfb"},"aliases":{"en":["%s"]}}}]}
                """.formatted(term, code, code, term, term, term);
    }

    JsonNode upload(String token, String bytes) throws Exception {
        return call(token, multipart("/api/patterns/upload")
                .file(new MockMultipartFile("file", "p.pdf", "application/pdf", bytes.getBytes())), 201);
    }

    List<String> codes(String token) throws Exception {
        JsonNode list = call(token, get("/api/techniques"), 200);
        return StreamSupport.stream(list.spliterator(), false).map(n -> n.get("code").asText()).toList();
    }

    @Test
    void learnedTechniqueIsUsableByItsUserOnly() throws Exception {
        String a = newUserToken();
        llm.reply(parsed(), defined());
        JsonNode up = upload(a, "A1" + term);
        assertThat(llm.calls).hasSize(2);                       // one parse, one define

        assertThat(codes(a)).contains(code);
        String b = newUserToken();
        assertThat(codes(b)).doesNotContain(code);

        JsonNode view = createProject(a, UUID.fromString(up.get("id").asText()));
        UUID step = stepId(view, 0, 1);
        JsonNode round = call(a, get("/api/projects/" + view.get("projectId").asText() + "/steps/" + step + "/rounds/0"), 200);
        assertThat(round.get("text").asText()).isEqualTo("(前后加针) × 10");
        assertThat(round.get("stsAfter").asInt()).isEqualTo(20);

        JsonNode entry = StreamSupport.stream(call(a, get("/api/techniques?lang=en"), 200).spliterator(), false)
                .filter(n -> n.get("code").asText().equals(code)).findFirst().orElseThrow();
        assertThat(entry.get("origin").asText()).isEqualTo("PERSONAL");
        assertThat(entry.get("alsoKnownAs").toString()).contains("前后加针" + term).doesNotContain("\"" + "knit front and back " + term + "\"");
    }

    @Test
    void secondUserTeachingTheSameStructurePromotesItAndLaterUsersNeedNoLlmCall() throws Exception {
        String a = newUserToken();
        llm.reply(parsed(), defined());
        upload(a, "A2" + term);

        String b = newUserToken();
        llm.reply(parsed(), defined());
        upload(b, "B2" + term);

        String c = newUserToken();
        llm.calls.clear();
        llm.reply(parsed());                                   // only the parse: the term is now global
        upload(c, "C2" + term);
        assertThat(llm.calls).hasSize(1);
        JsonNode entry = StreamSupport.stream(call(c, get("/api/techniques"), 200).spliterator(), false)
                .filter(n -> n.get("code").asText().equals(code)).findFirst().orElseThrow();
        assertThat(entry.get("origin").asText()).isEqualTo("GLOBAL");
    }

    @Test
    void nothingIsPromotedFromASingleUser() throws Exception {
        String a = newUserToken();
        llm.reply(parsed(), defined());
        upload(a, "A3" + term);
        assertThat(jdbc.sql("SELECT count(*) FROM technique WHERE code = ?").param(code).query(Long.class).single()).isZero();
    }

    @Test
    void learnedRowsAreInvisibleWithoutTheirUser() throws Exception {
        String a = newUserToken();
        llm.reply(parsed(), defined());
        upload(a, "A4" + term);
        assertThat(jdbc.sql("SELECT count(*) FROM user_technique").query(Long.class).single()).isZero();
        UUID other = users.upsertByAppleSub("other-" + UUID.randomUUID());
        assertThat(scope.call(other, () -> jdbc.sql("SELECT count(*) FROM user_technique_alias").query(Long.class).single()))
                .isZero();
    }
}
