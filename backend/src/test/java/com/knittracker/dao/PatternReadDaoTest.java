package com.knittracker.dao;

import static org.assertj.core.api.Assertions.assertThat;

import com.knittracker.PostgresTestBase;
import com.knittracker.ir.Fixtures;
import com.knittracker.ir.PatternIr;
import com.knittracker.security.UserScope;
import com.knittracker.service.PatternImportService;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;

class PatternReadDaoTest extends PostgresTestBase {

    @Autowired PatternImportService importer;
    @Autowired PatternReadDao reader;
    @Autowired AppUserDao users;
    @Autowired UserScope scope;

    static Stream<Path> fixtures() {
        return Fixtures.all().stream();
    }

    @ParameterizedTest
    @MethodSource("fixtures")
    void storedPatternReadsBackAsTheSameIr(Path file) {
        PatternIr ir = Fixtures.read(file);
        UUID user = users.upsertByAppleSub("read-" + UUID.randomUUID());
        UUID id = importer.importPattern(user, ir, "h", "v1");

        PatternReadDao.StoredPattern stored = scope.call(user, () -> reader.load(id)).orElseThrow();

        assertThat(stored.ir()).isEqualTo(ir);
        assertThat(stored.stepIds()).hasSameSizeAs(ir.sections());
    }
}
