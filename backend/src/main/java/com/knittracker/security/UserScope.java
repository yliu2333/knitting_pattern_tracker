package com.knittracker.security;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs work in a transaction with {@code app.user_id} set via SET LOCAL semantics, so Postgres RLS
 * scopes every query to that user. The setting dies with the transaction, so a pooled
 * connection can never carry one user's id into another request.
 */
@Component
public class UserScope {

    private final TransactionTemplate tx;
    private final JdbcClient jdbc;

    public UserScope(TransactionTemplate tx, JdbcClient jdbc) {
        this.tx = tx;
        this.jdbc = jdbc;
    }

    public <T> T call(UUID userId, Supplier<T> work) {
        Objects.requireNonNull(userId, "userId");
        return tx.execute(status -> {
            jdbc.sql("SELECT set_config('app.user_id', ?, true)").param(userId.toString()).query().singleValue();
            return work.get();
        });
    }

    public void run(UUID userId, Runnable work) {
        call(userId, () -> {
            work.run();
            return null;
        });
    }
}
