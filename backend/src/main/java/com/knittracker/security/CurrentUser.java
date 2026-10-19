package com.knittracker.security;

import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/** The only source of the caller's identity: the verified JWT subject. Never a request param or body. */
@Component
public class CurrentUser {

    public UUID id() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwt)) {
            throw new IllegalStateException("no authenticated user");
        }
        return UUID.fromString(jwt.getToken().getSubject());
    }
}
