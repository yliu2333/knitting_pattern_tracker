package com.knittracker.service.auth;

import com.knittracker.dao.AppUserDao;
import com.knittracker.security.AppleConfig.AppleTokenDecoder;
import com.knittracker.security.JwtService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;

/**
 * Sign in with Apple → our tokens. Access token: short-lived JWT. Refresh token: random, stored only as a
 * hash, single use (rotated on every refresh; reuse revokes all of the user's tokens).
 */
@Service
public class AuthService {

    public record Tokens(UUID userId, String accessToken, long expiresIn, String refreshToken) {}

    public static class AuthFailedException extends RuntimeException {
        public AuthFailedException(String message) {
            super(message);
        }
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AppleTokenDecoder apple;
    private final AppUserDao users;
    private final JwtService jwt;
    private final Duration accessTtl;
    private final Duration refreshTtl;

    public AuthService(AppleTokenDecoder apple, AppUserDao users, JwtService jwt,
                       @Value("${app.jwt.access-ttl}") Duration accessTtl,
                       @Value("${app.jwt.refresh-ttl}") Duration refreshTtl) {
        this.apple = apple;
        this.users = users;
        this.jwt = jwt;
        this.accessTtl = accessTtl;
        this.refreshTtl = refreshTtl;
    }

    /**
     * @param rawNonce the nonce the app generated; the app put sha256(rawNonce) into the Apple request,
     *                 so the token must carry that hash (stops replay of a captured identity token)
     */
    public Tokens signInWithApple(String identityToken, String rawNonce) {
        if (!apple.enabled()) throw new AuthFailedException("Sign in with Apple is not configured");
        Jwt token;
        try {
            token = apple.decoder().decode(identityToken);
        } catch (JwtException e) {
            throw new AuthFailedException("invalid Apple identity token");
        }
        if (rawNonce == null || !sha256Hex(rawNonce).equals(token.getClaimAsString("nonce"))) {
            throw new AuthFailedException("nonce mismatch");
        }
        return issue(users.upsertByAppleSub(token.getSubject()));
    }

    public Tokens refresh(String refreshToken) {
        UUID userId = users.useRefreshToken(sha256Hex(refreshToken));
        if (userId == null) throw new AuthFailedException("invalid refresh token");
        return issue(userId);
    }

    public void logout(String refreshToken) {
        users.revokeRefreshToken(sha256Hex(refreshToken));
    }

    private Tokens issue(UUID userId) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        users.issueRefreshToken(userId, sha256Hex(refresh), Instant.now().plus(refreshTtl));
        return new Tokens(userId, jwt.issue(userId, accessTtl), accessTtl.toSeconds(), refresh);
    }

    static String sha256Hex(String s) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
