package com.knittracker.api;

import com.knittracker.service.auth.AuthService;
import com.knittracker.service.auth.AuthService.Tokens;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Public endpoints (no JWT): exchange Apple's identity token or a refresh token for our tokens. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record AppleSignInRequest(@NotBlank String identityToken, @NotBlank String nonce) {}

    public record RefreshRequest(@NotBlank String refreshToken) {}

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/apple")
    public Tokens apple(@Valid @RequestBody AppleSignInRequest req) {
        return auth.signInWithApple(req.identityToken(), req.nonce());
    }

    @PostMapping("/refresh")
    public Tokens refresh(@Valid @RequestBody RefreshRequest req) {
        return auth.refresh(req.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshRequest req) {
        auth.logout(req.refreshToken());
    }
}
