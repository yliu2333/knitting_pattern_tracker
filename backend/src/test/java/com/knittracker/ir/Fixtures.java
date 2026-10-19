package com.knittracker.ir;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** Test helper: locates IR fixtures. Private (purchased) patterns live in fixtures/private, which may be absent. */
public final class Fixtures {

    public static final Path PUBLIC_DIR = Path.of("..", "fixtures");
    public static final Path PRIVATE_DIR = PUBLIC_DIR.resolve("private").resolve("patterns");

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    private Fixtures() {}

    public static List<Path> all() {
        return Stream.concat(list(PUBLIC_DIR), list(PRIVATE_DIR)).toList();
    }

    public static PatternIr read(Path file) {
        try {
            return MAPPER.readValue(file.toFile(), PatternIr.class);
        } catch (IOException e) {
            throw new UncheckedIOException(file.toString(), e);
        }
    }

    private static Stream<Path> list(Path dir) {
        if (!Files.isDirectory(dir)) return Stream.empty();
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(f -> f.getFileName().toString().endsWith(".ir.json")).sorted().toList().stream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
