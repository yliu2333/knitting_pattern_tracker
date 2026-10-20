package com.knittracker.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knittracker.PostgresTestBase;
import com.knittracker.dao.AppUserDao;
import com.knittracker.ir.Fixtures;
import com.knittracker.security.JwtService;
import java.nio.file.Files;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
abstract class ApiTestBase extends PostgresTestBase {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired AppUserDao users;
    @Autowired ObjectMapper mapper;

    String newUserToken() {
        UUID id = users.upsertByAppleSub("api-" + UUID.randomUUID());
        return jwt.issue(id, Duration.ofHours(1));
    }

    static MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder req) {
        return req.header("Authorization", "Bearer " + token);
    }

    /** Performs once; asserts the status; returns the JSON body (or null when empty). */
    JsonNode call(String token, MockHttpServletRequestBuilder req, int expectedStatus) throws Exception {
        var res = mvc.perform(as(token, req)).andReturn().getResponse();
        org.assertj.core.api.Assertions.assertThat(res.getStatus())
                .as(req.buildRequest(new org.springframework.mock.web.MockServletContext()).getRequestURI()
                        + " -> " + res.getContentAsString())
                .isEqualTo(expectedStatus);
        String body = res.getContentAsString();
        return body.isEmpty() ? null : mapper.readTree(body);
    }

    UUID uploadHat(String token) throws Exception {
        String ir = Files.readString(Fixtures.PUBLIC_DIR.resolve("sample-hat.ir.json"));
        JsonNode res = call(token, post("/api/patterns").contentType(MediaType.APPLICATION_JSON).content(ir), 201);
        return UUID.fromString(res.get("id").asText());
    }

    JsonNode createProject(String token, UUID patternId) throws Exception {
        return call(token, post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                .content("{\"patternId\":\"" + patternId + "\"}"), 201);
    }

    static UUID stepId(JsonNode view, int section, int step) {
        return UUID.fromString(view.get("sections").get(section).get("steps").get(step).get("id").asText());
    }
}
