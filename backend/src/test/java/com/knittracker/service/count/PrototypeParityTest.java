package com.knittracker.service.count;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.knittracker.ir.Fixtures;
import com.knittracker.service.count.PatternPlan.RoundPlan;
import com.knittracker.service.count.PatternPlan.StepPlan;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * The 泡芙 Tee prototype hard-coded its stitch counts; the simulator must reproduce them from the IR alone.
 * Oracle = numbers extracted from the prototype's own JS (private repo, expected/paofu-tee.prototype.json).
 */
@EnabledIf("privateFixturesPresent")
class PrototypeParityTest {

    static final Path PRIVATE = Fixtures.PUBLIC_DIR.resolve("private");
    static final Path IR = PRIVATE.resolve("patterns/paofu-tee.ir.json");
    static final Path ORACLE = PRIVATE.resolve("expected/paofu-tee.prototype.json");

    /** The prototype keeps the count after bind-off; we report 0 stitches left on the needle. */
    static final Set<String> AFTER_BIND_OFF = Set.of("sl1-bo", "sl2-bo", "bd-bo", "ends", "blk");

    /** Rows where the prototype states every k/p count literally (elsewhere it says "knit to marker"). */
    static final Set<String> LITERAL_ROWS = Set.of("ribset", "sr1", "sr2", "sr34", "srlast",
            "sl1-setup", "sl1-tub", "sl2-setup", "sl2-tub", "bd-tub");

    static List<StepPlan> steps;
    static JsonNode oracle;

    static boolean privateFixturesPresent() {
        return Files.exists(IR) && Files.exists(ORACLE);
    }

    @BeforeAll
    static void simulate() throws IOException {
        steps = StitchSimulator.simulate(Fixtures.read(IR)).steps();
        oracle = Fixtures.MAPPER.readTree(ORACLE.toFile());
    }

    @Test
    void sameNumberOfSteps() {
        assertThat(steps).hasSize(oracle.size());
    }

    @Test
    void startCountOfEveryStep() {
        for (int i = 0; i < oracle.size(); i++) {
            JsonNode o = oracle.get(i);
            if (AFTER_BIND_OFF.contains(o.get("id").asText())) continue;
            assertThat(steps.get(i).startSts()).as(o.get("id").asText()).isEqualTo(o.get("start").asInt());
        }
    }

    @Test
    void stitchCountAfterEveryRound() {
        for (int i = 0; i < oracle.size(); i++) {
            JsonNode o = oracle.get(i);
            if (AFTER_BIND_OFF.contains(o.get("id").asText())) continue;
            List<Integer> expected = ints(o.get("after"));
            List<Integer> actual = steps.get(i).rounds().stream().map(RoundPlan::stsAfter).toList();
            assertThat(actual).as(o.get("id").asText()).isEqualTo(expected);
        }
    }

    @Test
    void stitchesBetweenMarkersAtStartOfEachRound() {
        for (int i = 0; i < oracle.size(); i++) {
            JsonNode o = oracle.get(i);
            if (o.get("segs").isNull()) continue;
            List<RoundPlan> rounds = steps.get(i).rounds();
            for (int r = 0; r < o.get("segs").size(); r++) {
                assertThat(rounds.get(r).sectionsAtStart()).as(o.get("id").asText() + " round " + (r + 1))
                        .isEqualTo(ints(o.get("segs").get(r)));
            }
        }
    }

    @Test
    void literalKnitPurlCountsMatchPrototypeText() {
        for (int i = 0; i < oracle.size(); i++) {
            JsonNode o = oracle.get(i);
            if (!LITERAL_ROWS.contains(o.get("id").asText())) continue;
            List<RoundPlan> rounds = steps.get(i).rounds();
            for (int r = 0; r < o.get("rowNums").size(); r++) {
                assertThat(knitPurlCounts(rounds.get(r).segments())).as(o.get("id").asText() + " row " + (r + 1))
                        .isEqualTo(ints(o.get("rowNums").get(r)));
            }
        }
    }

    /** K/P counts in reading order, a kept REPEAT contributing its children once (as "(k1, p1) × 39" reads). */
    static List<Integer> knitPurlCounts(List<ResolvedSegment> segs) {
        List<Integer> out = new ArrayList<>();
        for (ResolvedSegment s : segs) {
            if (s.code().equals("K") || s.code().equals("P")) out.add(s.count());
            out.addAll(knitPurlCounts(s.children()));
        }
        return out;
    }

    static List<Integer> ints(JsonNode arr) {
        List<Integer> out = new ArrayList<>();
        arr.forEach(n -> out.add(n.asInt()));
        return out;
    }
}
