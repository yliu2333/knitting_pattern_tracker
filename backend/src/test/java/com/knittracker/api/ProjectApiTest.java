package com.knittracker.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.knittracker.ir.Fixtures;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

class ProjectApiTest extends ApiTestBase {

    @Autowired JwtEncoder encoder;

    @Test
    void trackHatFromCastOnThroughRewindAndUndo() throws Exception {
        String me = newUserToken();
        UUID pattern = uploadHat(me);
        JsonNode view = createProject(me, pattern);
        String p = "/api/projects/" + view.get("projectId").asText();
        UUID castOn = stepId(view, 0, 0);
        UUID rib = stepId(view, 0, 1);
        UUID body = stepId(view, 1, 0);

        assertThat(view.get("currentStepId").asText()).isEqualTo(castOn.toString());
        assertThat(view.get("totalSteps").asInt()).isEqualTo(7);
        assertThat(view.get("sections").get(2).get("steps").get(0).get("endSts").asInt()).isEqualTo(48);

        JsonNode round = call(me, get(p + "/steps/" + castOn + "/rounds/0"), 200);
        assertThat(round.get("text").asText()).isEqualTo("长尾起针 起 96 针，放圈首记号圈 BOR，连成圈（不要扭圈）");
        assertThat(call(me, get(p + "/steps/" + rib + "/rounds/3?lang=en"), 200).get("text").asText())
                .isEqualTo("(k1, p1) × 48");

        view = call(me, post(p + "/steps/" + castOn + "/advance"), 200);
        assertThat(view.get("currentStepId").asText()).isEqualTo(rib.toString());
        for (int i = 0; i < 12; i++) view = call(me, post(p + "/steps/" + rib + "/advance"), 200);
        call(me, post(p + "/steps/" + rib + "/advance"), 409);
        assertThat(view.get("currentStepId").asText()).isEqualTo(body.toString());

        view = call(me, post(p + "/steps/" + body + "/advance"), 200);
        view = call(me, post(p + "/steps/" + body + "/finish"), 200);
        assertThat(view.get("lastAction").get("type").asText()).isEqualTo("FINISH");

        // 拆线 back to round 5 of the rib: rib = 5/12, body cleared
        view = call(me, post(p + "/steps/" + rib + "/rewind?round=5"), 200);
        assertThat(step(view, 0, 1).get("done").asInt()).isEqualTo(5);
        assertThat(step(view, 1, 0).get("done").asInt()).isZero();
        assertThat(step(view, 1, 0).get("finished").asBoolean()).isFalse();

        // undo restores everything the rewind cleared
        view = call(me, post(p + "/undo"), 200);
        assertThat(step(view, 0, 1).get("done").asInt()).isEqualTo(12);
        assertThat(step(view, 1, 0).get("finished").asBoolean()).isTrue();
        assertThat(view.get("lastAction").get("type").asText()).isEqualTo("FINISH");

        view = call(me, post(p + "/undo"), 200);
        assertThat(step(view, 1, 0).get("finished").asBoolean()).isFalse();
        assertThat(view.get("percent").asDouble()).isGreaterThan(0);
    }

    @Test
    void notesSurviveRewind() throws Exception {
        String me = newUserToken();
        JsonNode view = createProject(me, uploadHat(me));
        String p = "/api/projects/" + view.get("projectId").asText();
        UUID castOn = stepId(view, 0, 0);
        UUID rib = stepId(view, 0, 1);

        call(me, post(p + "/steps/" + castOn + "/advance"), 200);
        call(me, put(p + "/steps/" + rib + "/note").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"多织了 2 圈\"}"), 200);
        view = call(me, post(p + "/steps/" + castOn + "/rewind"), 200);
        assertThat(step(view, 0, 1).get("note").asText()).isEqualTo("多织了 2 圈");
        assertThat(view.get("currentStepId").asText()).isEqualTo(castOn.toString());
    }

    @Test
    void uploadingTheSameIrTwiceReturnsTheExistingPattern() throws Exception {
        String me = newUserToken();
        UUID first = uploadHat(me);
        String ir = Files.readString(Fixtures.PUBLIC_DIR.resolve("sample-hat.ir.json"));
        JsonNode again = call(me, post("/api/patterns").contentType(MediaType.APPLICATION_JSON).content(ir), 200);
        assertThat(again.get("id").asText()).isEqualTo(first.toString());
    }

    @Test
    void invalidIrIsRejectedWithReasons() throws Exception {
        JsonNode res = call(newUserToken(), post("/api/patterns").contentType(MediaType.APPLICATION_JSON)
                .content("{\"irVersion\":1,\"title\":\"x\",\"craft\":\"KNIT\",\"sourceType\":\"TEXT_PDF\",\"sourceLang\":\"en\",\"sections\":[]}"), 400);
        assertThat(res.get("errors").toString()).contains("at least one section");
    }

    @Test
    void requestsWithoutAValidTokenAreRejected() throws Exception {
        assertThat(mvc.perform(get("/api/projects")).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(get("/api/projects").header("Authorization", "Bearer not-a-jwt"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        Instant now = Instant.now();
        String expired = token("knit-tracker", now.minusSeconds(3600), now.minusSeconds(1800));
        String foreign = token("someone-else", now, now.plusSeconds(3600));
        for (String t : List.of(expired, foreign)) {
            assertThat(mvc.perform(get("/api/projects").header("Authorization", "Bearer " + t))
                    .andReturn().getResponse().getStatus()).isEqualTo(401);
        }
    }

    String token(String issuer, Instant issued, Instant expires) {
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(issuer).subject(UUID.randomUUID().toString())
                .issuedAt(issued).expiresAt(expires).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    static JsonNode step(JsonNode view, int section, int step) {
        return view.get("sections").get(section).get("steps").get(step);
    }
}
