package com.knittracker.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.knittracker.security.UserScope;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** For every endpoint: user B touching user A's resources gets 404, and A's data is unchanged. */
class ApiIsolationTest extends ApiTestBase {

    @Autowired UserScope scope;
    @Autowired JdbcClient jdbc;

    String a;
    String b;
    UUID patternA;
    String projectA;
    UUID stepA;

    @BeforeEach
    void setUp() throws Exception {
        a = newUserToken();
        b = newUserToken();
        patternA = uploadHat(a);
        JsonNode view = createProject(a, patternA);
        projectA = "/api/projects/" + view.get("projectId").asText();
        stepA = stepId(view, 0, 0);
        call(a, post(projectA + "/steps/" + stepA + "/advance"), 200);
    }

    @Test
    void everyProjectEndpointIs404ForAnotherUser() throws Exception {
        List<MockHttpServletRequestBuilder> requests = List.of(
                get(projectA),
                get(projectA + "/steps/" + stepA + "/rounds/0"),
                post(projectA + "/steps/" + stepA + "/advance"),
                post(projectA + "/steps/" + stepA + "/retreat"),
                post(projectA + "/steps/" + stepA + "/finish"),
                post(projectA + "/steps/" + stepA + "/rewind"),
                put(projectA + "/steps/" + stepA + "/note").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"x\"}"),
                post(projectA + "/undo"));
        for (MockHttpServletRequestBuilder req : requests) {
            call(b, req, 404);
        }
        JsonNode stillA = call(a, get(projectA), 200);
        assertThat(stillA.get("sections").get(0).get("steps").get(0).get("done").asInt()).isEqualTo(1);
        assertThat(stillA.get("lastAction").get("type").asText()).isEqualTo("ADVANCE");
    }

    @Test
    void cannotCreateAProjectOnAnotherUsersPattern() throws Exception {
        call(b, post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                .content("{\"patternId\":\"" + patternA + "\"}"), 404);
    }

    @Test
    void anotherUsersStepCannotBeUsedInMyProject() throws Exception {
        JsonNode mine = createProject(b, uploadHat(b));
        String p = "/api/projects/" + mine.get("projectId").asText();
        call(b, post(p + "/steps/" + stepA + "/advance"), 404);
        call(b, get(p + "/steps/" + stepA + "/rounds/0"), 404);
        call(b, put(p + "/steps/" + stepA + "/note").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"x\"}"), 404);
    }

    @Test
    void listsOnlyShowOwnData() throws Exception {
        assertThat(call(b, get("/api/projects"), 200)).isEmpty();
        assertThat(call(b, get("/api/patterns"), 200)).isEmpty();
        assertThat(call(a, get("/api/projects"), 200)).hasSize(1);
    }

    @Test
    void sameIrUploadedByTwoUsersMakesTwoPatterns() throws Exception {
        UUID patternB = uploadHat(b);
        assertThat(patternB).isNotEqualTo(patternA);
    }

    @Test
    void databaseRejectsChildRowsPointingAtAnotherUsersParent() {
        UUID userB = users.upsertByAppleSub("fk-" + UUID.randomUUID());
        assertThatThrownBy(() -> scope.run(userB, () -> jdbc.sql(
                "INSERT INTO project (user_id, pattern_id) VALUES (?, ?)").params(userB, patternA).update()))
                .rootCause().hasMessageContaining("violates foreign key constraint");
    }
}
