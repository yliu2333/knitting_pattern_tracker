package com.knittracker.security;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/** Verifies Apple identity tokens: RS256 signature from Apple's JWKS, issuer, expiry, and our bundle id as audience. */
@Configuration
public class AppleConfig {

    @Bean
    AppleTokenDecoder appleTokenDecoder(@Value("${app.apple.jwks-uri}") String jwksUri,
                                        @Value("${app.apple.issuer}") String issuer,
                                        @Value("${app.apple.client-ids}") List<String> clientIds) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri).build();
        decoder.setJwtValidator(validator(issuer, clientIds));
        return new AppleTokenDecoder(decoder, !clientIds.isEmpty());
    }

    public static DelegatingOAuth2TokenValidator<org.springframework.security.oauth2.jwt.Jwt> validator(
            String issuer, List<String> clientIds) {
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                jwt -> jwt.getAudience() != null && jwt.getAudience().stream().anyMatch(clientIds::contains)
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "audience", null)));
    }

    /** Separate type so it never gets confused with the decoder for our own access tokens. */
    public record AppleTokenDecoder(JwtDecoder decoder, boolean enabled) {}
}
