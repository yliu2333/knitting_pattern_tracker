package com.knittracker.dao;

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
}
