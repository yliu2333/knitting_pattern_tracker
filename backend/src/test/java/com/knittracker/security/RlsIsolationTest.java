package com.knittracker.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knittracker.PostgresTestBase;
import com.knittracker.dao.AppUserDao;
import com.knittracker.ir.Fixtures;
import com.knittracker.ir.PatternIr;
import com.knittracker.service.PatternImportService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class RlsIsolationTest extends PostgresTestBase {

    static final List<String> USER_TABLES = List.of("pattern", "section", "step", "step_row", "segment",
            "project", "progress", "progress_event", "yarn_log", "embedding");

    @Autowired PatternImportService importer;
    @Autowired AppUserDao users;
    @Autowired UserScope scope;
    @Autowired JdbcClient jdbc;

    PatternIr hat = Fixtures.read(Fixtures.PUBLIC_DIR.resolve("sample-hat.ir.json"));
    UUID userA;
    UUID userB;

    @BeforeEach
    void users() {
        userA = users.upsertByAppleSub("apple-A-" + UUID.randomUUID());
        userB = users.upsertByAppleSub("apple-B-" + UUID.randomUUID());
    }

    @Test
    void userBSeesNoneOfUserAsRows() {
        UUID patternId = importer.importPattern(userA, hat, "hash-1", "v1");

        for (String t : List.of("pattern", "section", "step", "step_row", "segment")) {
            assertThat(scope.call(userA, () -> count(t))).as("A sees own " + t).isPositive();
            assertThat(scope.call(userB, () -> count(t))).as("B sees A's " + t).isZero();
        }
        assertThat(scope.call(userB, () -> jdbc.sql("SELECT count(*) FROM pattern WHERE id = ?")
                .param(patternId).query(Long.class).single())).isZero();
    }

    @Test
    void noUserSetMeansZeroRows() {
        importer.importPattern(userA, hat, "hash-2", "v1");
        for (String t : USER_TABLES) {
            assertThat(count(t)).as(t + " without app.user_id").isZero();
        }
        assertThat(count("app_user")).isZero();
    }

    @Test
    void userIdDoesNotLeakPastTransaction() {
        importer.importPattern(userA, hat, "hash-3", "v1");
        scope.call(userA, () -> count("pattern"));
        // Same pooled connection, new transaction: SET LOCAL value must be gone.
        assertThat(count("pattern")).isZero();
    }

    @Test
    void cannotInsertRowsOwnedByAnotherUser() {
        assertThatThrownBy(() -> scope.run(userB, () -> jdbc.sql("""
                INSERT INTO pattern (user_id, title, craft, source_type, source_lang, file_hash, parser_version)
                VALUES (?, 'x', 'KNIT', 'TEXT_PDF', 'en', 'h', 'v1')""").param(userA).update()))
                .rootCause().hasMessageContaining("violates row-level security policy");
    }

    @Test
    void cannotUpdateOrDeleteAnotherUsersRows() {
        importer.importPattern(userA, hat, "hash-4", "v1");
        int updated = scope.call(userB, () -> jdbc.sql("UPDATE pattern SET title = 'pwned'").update());
        int deleted = scope.call(userB, () -> jdbc.sql("DELETE FROM segment").update());
        assertThat(updated).isZero();
        assertThat(deleted).isZero();
        assertThat(scope.call(userA, () -> jdbc.sql("SELECT title FROM pattern").query(String.class).list()))
                .containsOnly("Sample Beanie");
    }

    @Test
    void sameFileHashIsCachedPerUserNotShared() {
        importer.importPattern(userA, hat, "same-hash", "v1");
        importer.importPattern(userB, hat, "same-hash", "v1");
        assertThat(scope.call(userA, () -> count("pattern"))).isEqualTo(1);
        assertThat(scope.call(userB, () -> count("pattern"))).isEqualTo(1);
    }

    @Test
    void everyUserOwnedTableHasForcedRls() {
        List<String> unprotected = jdbc.sql("""
                SELECT c.relname FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = 'public'
                WHERE c.relkind = 'r'
                  AND EXISTS (SELECT 1 FROM pg_attribute a WHERE a.attrelid = c.oid AND a.attname = 'user_id')
                  AND NOT (c.relrowsecurity AND c.relforcerowsecurity)""").query(String.class).list();
        // refresh_token is looked up before the user is known: not FORCEd, but the app role has no access at all.
        assertThat(unprotected).containsOnly("refresh_token");
        assertThat(jdbc.sql("""
                SELECT has_table_privilege('refresh_token', 'SELECT')
                    OR has_table_privilege('refresh_token', 'INSERT')
                    OR has_table_privilege('refresh_token', 'UPDATE')
                    OR has_table_privilege('refresh_token', 'DELETE')""").query(Boolean.class).single()).isFalse();
        assertThat(jdbc.sql("SELECT relrowsecurity FROM pg_class WHERE relname = 'app_user'")
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    void appRoleCannotBypassRls() {
        assertThat(jdbc.sql("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user")
                .query(Boolean.class).single()).isFalse();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM pg_tables WHERE schemaname = 'public' AND tableowner = current_user""")
                .query(Long.class).single()).isZero();
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }
}
