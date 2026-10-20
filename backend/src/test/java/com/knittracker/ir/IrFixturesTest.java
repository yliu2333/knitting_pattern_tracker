package com.knittracker.ir;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class IrFixturesTest {

    static Stream<Path> fixtures() {
        return Fixtures.all().stream();
    }

    @Test
    void publicSampleExists() {
        assertThat(Fixtures.all()).anyMatch(p -> p.endsWith("sample-hat.ir.json"));
    }

    @ParameterizedTest
    @MethodSource("fixtures")
    void fixtureIsValidIr(Path file) {
        assertThat(IrValidator.validate(Fixtures.read(file))).as(file.toString()).isEmpty();
    }
}
