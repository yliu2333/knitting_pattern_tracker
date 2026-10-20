package com.knittracker.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knittracker.PostgresTestBase;
import com.knittracker.dao.AppUserDao;
import com.knittracker.ir.Fixtures;
import com.knittracker.ir.PatternIr;
import com.knittracker.ir.SegmentIr;
import com.knittracker.security.UserScope;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class PatternImportServiceTest extends PostgresTestBase {

    @Autowired PatternImportService importer;
    @Autowired AppUserDao users;
    @Autowired UserScope scope;
    @Autowired JdbcClient jdbc;

    static Stream<Path> fixtures() {
        return Fixtures.all().stream();
    }

    @ParameterizedTest
    @MethodSource("fixtures")
    void importsEveryFixtureCompletely(Path file) {
        PatternIr ir = Fixtures.read(file);
        UUID user = users.upsertByAppleSub("import-" + UUID.randomUUID());
        UUID patternId = importer.importPattern(user, ir, file.getFileName().toString(), "fixture");

        long steps = ir.sections().stream().mapToLong(s -> s.steps().size()).sum();
        long rows = ir.sections().stream().flatMap(s -> s.steps().stream()).mapToLong(t -> t.rows().size()).sum();
        long segments = ir.sections().stream().flatMap(s -> s.steps().stream()).flatMap(t -> t.rows().stream())
                .mapToLong(r -> countSegments(r.segments())).sum();

        scope.run(user, () -> {
            assertThat(count("SELECT count(*) FROM section WHERE pattern_id = ?", patternId)).isEqualTo(ir.sections().size());
            assertThat(count("""
                    SELECT count(*) FROM step t JOIN section s ON s.id = t.section_id WHERE s.pattern_id = ?""", patternId))
                    .isEqualTo(steps);
            assertThat(count("SELECT count(*) FROM step_row")).isEqualTo(rows);
            assertThat(count("SELECT count(*) FROM segment")).isEqualTo(segments);
        });
    }

    @Test
    void rejectsInvalidIrBeforeTouchingTheDb() {
        PatternIr hat = Fixtures.read(Fixtures.PUBLIC_DIR.resolve("sample-hat.ir.json"));
        PatternIr bad = new PatternIr(2, hat.title(), hat.craft(), hat.sourceType(), hat.sourceLang(), hat.gauge(), hat.sections());
        UUID user = users.upsertByAppleSub("bad-" + UUID.randomUUID());
        assertThatThrownBy(() -> importer.importPattern(user, bad, "h", "v1"))
                .isInstanceOf(PatternImportService.InvalidIrException.class);
        assertThat(scope.call(user, () -> count("SELECT count(*) FROM pattern"))).isZero();
    }

    private static long countSegments(List<SegmentIr> segs) {
        return segs.stream().mapToLong(g -> 1 + countSegments(g.childrenOrEmpty())).sum();
    }

    private long count(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Long.class).single();
    }
}
