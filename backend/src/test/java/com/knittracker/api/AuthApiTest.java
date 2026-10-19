package com.knittracker.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.knittracker.security.AppleConfig;
import com.knittracker.security.AppleConfig.AppleTokenDecoder;
import com.knittracker.security.UserScope;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@Import(AuthApiTest.FakeApple.class)
class AuthApiTest extends ApiTestBase {

    static final String APPLE = "https://appleid.apple.com";
    static final String BUNDLE = "com.example.knit";
    static final KeyPair APPLE_KEY = rsa();
    static final KeyPair OTHER_KEY = rsa();

    /** Stands in for Apple: same validation rules as production, but trusts a test RSA key. */
    @TestConfiguration
    static class FakeApple {
        @Bean
        @Primary
        AppleTokenDecoder fakeAppleDecoder() {
            NimbusJwtDecoder d = NimbusJwtDecoder.withPublicKey((RSAPublicKey) APPLE_KEY.getPublic()).build();
            d.setJwtValidator(AppleConfig.validator(APPLE, List.of(BUNDLE)));
            return new AppleTokenDecoder(d, true);
        }
    }

    @Autowired UserScope scope;
    @Autowired JdbcClient jdbc;

    @Test
    void signInIssuesTokensAndTheSameAppleUserMapsToTheSameAccount() throws Exception {
        String sub = "apple-" + UUID.randomUUID();
        JsonNode first = signIn(appleToken(APPLE_KEY, APPLE, BUNDLE, sub, "n1", 600), "n1", 200);
        JsonNode second = signIn(appleToken(APPLE_KEY, APPLE, BUNDLE, sub, "n2", 600), "n2", 200);

        assertThat(second.get("userId")).isEqualTo(first.get("userId"));
        assertThat(first.get("expiresIn").asLong()).isEqualTo(3600);
        JsonNode me = call(first.get("accessToken").asText(), get("/api/me"), 200);
        assertThat(me.get("id")).isEqualTo(first.get("userId"));
        assertThat(me.get("uiLang").asText()).isEqualTo("zh");
    }

    @Test
    void badAppleTokensAreRejected() throws Exception {
        String sub = "apple-" + UUID.randomUUID();
        signIn(appleToken(APPLE_KEY, APPLE, "com.someone.else", sub, "n", 600), "n", 401);
        signIn(appleToken(APPLE_KEY, "https://evil.example", BUNDLE, sub, "n", 600), "n", 401);
        signIn(appleToken(OTHER_KEY, APPLE, BUNDLE, sub, "n", 600), "n", 401);
        signIn(appleToken(APPLE_KEY, APPLE, BUNDLE, sub, "n", -600), "n", 401);
        signIn(appleToken(APPLE_KEY, APPLE, BUNDLE, sub, "n", 600), "other-nonce", 401);
        signIn("garbage", "n", 401);
    }

    @Test
    void refreshRotatesAndReuseRevokesEverything() throws Exception {
        JsonNode login = signIn(appleToken(APPLE_KEY, APPLE, BUNDLE, "apple-" + UUID.randomUUID(), "n", 600), "n", 200);
        String r1 = login.get("refreshToken").asText();

        JsonNode next = refresh(r1, 200);
        String r2 = next.get("refreshToken").asText();
        assertThat(r2).isNotEqualTo(r1);
        assertThat(next.get("userId")).isEqualTo(login.get("userId"));

        refresh(r1, 401);          // stolen old token replayed...
        refresh(r2, 401);          // ...kills the legitimate one too
    }

    @Test
    void logoutRevokesTheRefreshToken() throws Exception {
        JsonNode login = signIn(appleToken(APPLE_KEY, APPLE, BUNDLE, "apple-" + UUID.randomUUID(), "n", 600), "n", 200);
        String r = login.get("refreshToken").asText();
        assertThat(mvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + r + "\"}")).andReturn().getResponse().getStatus()).isEqualTo(204);
        refresh(r, 401);
    }

    @Test
    void uiLanguageCanBeChangedToASupportedOne() throws Exception {
        String token = newUserToken();
        JsonNode me = call(token, patch("/api/me").contentType(MediaType.APPLICATION_JSON).content("{\"uiLang\":\"en\"}"), 200);
        assertThat(me.get("uiLang").asText()).isEqualTo("en");
        call(token, patch("/api/me").contentType(MediaType.APPLICATION_JSON).content("{\"uiLang\":\"xx\"}"), 400);
    }

    @Test
    void deletingTheAccountRemovesAllItsDataAndNothingElse() throws Exception {
        JsonNode login = signIn(appleToken(APPLE_KEY, APPLE, BUNDLE, "apple-" + UUID.randomUUID(), "n", 600), "n", 200);
        String token = login.get("accessToken").asText();
        UUID me = UUID.fromString(login.get("userId").asText());
        createProject(token, uploadHat(token));
        String other = newUserToken();
        createProject(other, uploadHat(other));

        call(token, delete("/api/me"), 204);

        for (String t : List.of("pattern", "section", "step", "step_row", "segment", "project", "progress_event")) {
            long n = scope.call(me, () -> jdbc.sql("SELECT count(*) FROM " + t).query(Long.class).single());
            assertThat(n).as(t).isZero();
        }
        call(token, get("/api/me"), 404);
        refresh(login.get("refreshToken").asText(), 401);
        assertThat(call(other, get("/api/projects"), 200)).hasSize(1);
    }

    @Test
    void appleLoginIsOffWhenNoBundleIdIsConfigured() {
        var auth = new com.knittracker.service.auth.AuthService(new AppleTokenDecoder(null, false), null, null,
                java.time.Duration.ofHours(1), java.time.Duration.ofDays(1));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> auth.signInWithApple("x", "n"))
                .hasMessageContaining("not configured");
    }

    JsonNode signIn(String identityToken, String nonce, int status) throws Exception {
        return postJson("/api/auth/apple", "{\"identityToken\":\"" + identityToken + "\",\"nonce\":\"" + nonce + "\"}", status);
    }

    JsonNode refresh(String refreshToken, int status) throws Exception {
        return postJson("/api/auth/refresh", "{\"refreshToken\":\"" + refreshToken + "\"}", status);
    }

    JsonNode postJson(String url, String body, int status) throws Exception {
        var res = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
        assertThat(res.getStatus()).as(url + " " + res.getContentAsString()).isEqualTo(status);
        return mapper.readTree(res.getContentAsString().isEmpty() ? "null" : res.getContentAsString());
    }

    static String appleToken(KeyPair key, String issuer, String audience, String sub, String rawNonce, long ttlSeconds)
            throws Exception {
        RSAKey jwk = new RSAKey.Builder((RSAPublicKey) key.getPublic()).privateKey(key.getPrivate()).build();
        var encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(ttlSeconds);
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(issuer).audience(List.of(audience)).subject(sub)
                .issuedAt(ttlSeconds > 0 ? now : exp.minusSeconds(60)).expiresAt(exp)
                .claim("nonce", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(rawNonce.getBytes(StandardCharsets.UTF_8))))
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))
                .getTokenValue();
    }

    static KeyPair rsa() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return g.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
