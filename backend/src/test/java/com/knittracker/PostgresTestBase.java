package com.knittracker;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * One Postgres container per JVM. Flyway migrates as the owner; the app datasource connects as
 * kpt_app (RLS-subject), exactly as in production.
 */
@SpringBootTest
public abstract class PostgresTestBase {

    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("knit")
            .withUsername("kpt_owner")
            .withPassword("kpt_owner")
            .withCopyFileToContainer(MountableFile.forHostPath("db/init/01-roles.sql"),
                    "/docker-entrypoint-initdb.d/01-roles.sql");

    static {
        PG.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", () -> "kpt_app");
        r.add("spring.datasource.password", () -> "kpt_app");
        r.add("spring.flyway.url", PG::getJdbcUrl);
        r.add("spring.flyway.user", PG::getUsername);
        r.add("spring.flyway.password", PG::getPassword);
    }
}
