package com.knittracker.service.count;

import com.fasterxml.jackson.databind.JsonNode;
import com.knittracker.ir.Fixtures;
import com.knittracker.ir.PatternIr;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Stitch counts a pattern states in its own text ("合計274目になる"), in knitting order. Any IR for that
 * pattern — hand-written or parsed by the LLM — must reproduce them as a subsequence of its step end counts,
 * so the check doesn't depend on how finely the IR splits steps.
 */
public record ExpectedCounts(String name, String source, List<Integer> endStsInOrder) {

    public static final Path DIR = Fixtures.PUBLIC_DIR.resolve("private").resolve("expected");

    public static List<ExpectedCounts> all() {
        if (!Files.isDirectory(DIR)) return List.of();
        try (Stream<Path> files = Files.list(DIR)) {
            return files.filter(f -> f.getFileName().toString().endsWith(".expected.json")).sorted()
                    .map(ExpectedCounts::read).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static ExpectedCounts read(Path file) {
        try {
            JsonNode n = Fixtures.MAPPER.readTree(file.toFile());
            List<Integer> counts = new ArrayList<>();
            n.get("endStsInOrder").forEach(c -> counts.add(c.asInt()));
            String name = file.getFileName().toString().replace(".expected.json", "");
            return new ExpectedCounts(name, n.path("source").asText(null), counts);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Empty when every expected count appears in order; otherwise the first one that is missing. */
    public List<String> check(PatternIr ir) {
        List<Integer> ends = StitchSimulator.simulate(ir).steps().stream().map(PatternPlan.StepPlan::endSts).toList();
        int i = 0;
        for (int end : ends) {
            if (i < endStsInOrder.size() && end == endStsInOrder.get(i)) i++;
        }
        if (i == endStsInOrder.size()) return List.of();
        return List.of("expected stitch count " + endStsInOrder.get(i) + " (#" + (i + 1) + " of " + endStsInOrder
                + ") not reached; step end counts were " + ends);
    }

    @Override
    public String toString() {
        return name;
    }
}
