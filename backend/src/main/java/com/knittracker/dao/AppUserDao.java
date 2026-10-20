package com.knittracker.dao;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AppUserDao {

    private final JdbcClient jdbc;

    public AppUserDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Sign-in lookup; the only query allowed before app.user_id is known (SECURITY DEFINER function). */
    public UUID upsertByAppleSub(String appleSub) {
        return jdbc.sql("SELECT app_user_upsert(?)").param(appleSub).query(UUID.class).single();
    }

    // Refresh tokens: the app role can't read the table; these SECURITY DEFINER functions are the only access.

    public void issueRefreshToken(UUID userId, String tokenHash, Instant expiresAt) {
        jdbc.sql("SELECT refresh_token_issue(?, ?, ?)").params(userId, tokenHash, Timestamp.from(expiresAt))
                .query((rs, i) -> 0).list();   // void function: result is a single NULL
    }

    /** Marks the token used and returns its user, or null if unknown, expired, or reused (which revokes all). */
    public UUID useRefreshToken(String tokenHash) {
        java.util.List<UUID> r = jdbc.sql("SELECT refresh_token_use(?)").param(tokenHash).query(UUID.class).list();
        return r.isEmpty() ? null : r.getFirst();
    }

    public void revokeRefreshToken(String tokenHash) {
        jdbc.sql("SELECT refresh_token_revoke(?)").param(tokenHash).query((rs, i) -> 0).list();
    }

    // The calls below run under UserScope: RLS on app_user limits them to the caller's own row.

    public record Me(UUID id, String uiLang, String createdAt) {}

    public Optional<Me> me() {
        return jdbc.sql("SELECT id, ui_lang, created_at::text FROM app_user")
                .query((rs, i) -> new Me(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)))
                .optional();
    }

    public void setUiLang(String lang) {
        jdbc.sql("UPDATE app_user SET ui_lang = ?").param(lang).update();
    }

    /** Deletes the caller and, by cascade, every pattern, project and token they own. */
    public int deleteMe() {
        return jdbc.sql("DELETE FROM app_user").update();
    }
}
