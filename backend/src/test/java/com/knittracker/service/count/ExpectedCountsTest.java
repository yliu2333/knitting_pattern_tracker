package com.knittracker.service.count;

import static org.assertj.core.api.Assertions.assertThat;

import com.knittracker.ir.Fixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Hand-written IR of each purchased pattern (private) reproduces the counts the pattern states. */
class ExpectedCountsTest {

    static Stream<ExpectedCounts> expectations() {
        return ExpectedCounts.all().stream();
    }

    @ParameterizedTest
    @MethodSource("expectations")
    void handWrittenIrReachesEveryStatedCount(ExpectedCounts expected) {
        Path ir = Fixtures.PRIVATE_DIR.resolve(expected.name() + ".ir.json");
        if (!Files.exists(ir)) return;
        assertThat(expected.check(Fixtures.read(ir))).isEmpty();
    }
}
