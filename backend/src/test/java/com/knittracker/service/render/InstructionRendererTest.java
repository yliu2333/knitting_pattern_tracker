package com.knittracker.service.render;

import static org.assertj.core.api.Assertions.assertThat;

import com.knittracker.PostgresTestBase;
import com.knittracker.dao.GlossaryDao;
import com.knittracker.ir.Fixtures;
import com.knittracker.ir.PatternIr;
import com.knittracker.ir.SectionIr;
import com.knittracker.ir.StepIr;
import com.knittracker.service.count.PatternPlan;
import com.knittracker.service.count.PatternPlan.RoundPlan;
import com.knittracker.service.count.ResolvedSegment;
import com.knittracker.service.count.StitchSimulator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class InstructionRendererTest extends PostgresTestBase {

    @Autowired GlossaryDao glossaries;

    PatternIr hat = Fixtures.read(Fixtures.PUBLIC_DIR.resolve("sample-hat.ir.json"));
    PatternPlan plan = StitchSimulator.simulate(hat);

    String line(int section, int step, int round, String lang) {
        RoundPlan r = plan.sections().get(section).get(step).rounds().get(round);
        return InstructionRenderer.render(r.segments(), glossaries.load(lang));
    }

    @Test
    void castOn() {
        assertThat(line(0, 0, 0, "zh")).isEqualTo("长尾起针 起 96 针，放圈首记号圈 BOR，连成圈（不要扭圈）");
        assertThat(line(0, 0, 0, "en")).isEqualTo("long-tail cast on 96 sts, pm BOR, join in the round, careful not to twist");
    }

    @Test
    void ribIsACompactRepeat() {
        assertThat(line(0, 1, 0, "zh")).isEqualTo("(下针 1，上针 1) × 48");
        assertThat(line(0, 1, 0, "en")).isEqualTo("(k1, p1) × 48");
    }

    @Test
    void markerSetup() {
        assertThat(line(1, 1, 0, "zh")).isEqualTo("(下针 12，放记号圈) × 7，下针 12");
        assertThat(line(1, 1, 0, "en")).isEqualTo("(k12, pm) × 7, k12");
    }

    @Test
    void crownDecreasesStateLiteralCountsThatShrinkEachGroup() {
        assertThat(line(2, 0, 0, "zh")).isEqualTo("(下针 10，左上2并1，移记号圈) × 8");
        assertThat(line(2, 0, 1, "zh")).isEqualTo("(下针 11，移记号圈) × 7，下针 11");
        assertThat(line(2, 0, 2, "en")).isEqualTo("(k9, k2tog, sm) × 8");
        assertThat(line(2, 0, 10, "en")).isEqualTo("(k5, k2tog, sm) × 8");
        assertThat(line(2, 0, 11, "en")).isEqualTo("(k6, sm) × 7, k6");
    }

    @Test
    void finish() {
        assertThat(line(2, 1, 0, "en")).isEqualTo("(k2tog) × 24");
        assertThat(line(2, 2, 0, "zh")).isEqualTo("剪线，穿过剩余 24 针收紧");
    }

    @Test
    void foldJoinReadsNaturally() {
        assertThat(InstructionRenderer.render(List.of(
                ResolvedSegment.repeat(78, List.of(
                        ResolvedSegment.of("FOLD_JOIN", 1, null, null, null)))),
                glossaries.load("zh"))).isEqualTo("(对折合并) × 78");
    }

    @Test
    void stitchCountsPerStep() {
        List<Integer> ends = plan.steps().stream().map(PatternPlan.StepPlan::endSts).toList();
        assertThat(ends).containsExactly(96, 96, 96, 96, 48, 24, 0);
    }

    @Test
    void everyCodeInEveryFixtureHasATemplate() throws IOException {
        for (Path f : Fixtures.all()) {
            PatternIr ir = Fixtures.read(f);
            PatternPlan p = StitchSimulator.simulate(ir);
            for (String lang : List.of("zh", "en")) {
                List<String> lines = dump(ir, p, glossaries.load(lang));
                assertThat(lines).as(f + " " + lang).noneMatch(l -> l.contains("⟨"));
                if (f.toString().contains("private")) {
                    Files.write(Path.of("build", f.getFileName() + "." + lang + ".txt"), lines);
                }
            }
        }
    }

    static List<String> dump(PatternIr ir, PatternPlan p, Glossary g) {
        List<String> out = new ArrayList<>();
        for (int s = 0; s < ir.sections().size(); s++) {
            SectionIr sec = ir.sections().get(s);
            out.add("## " + sec.nameSrc());
            for (int t = 0; t < sec.steps().size(); t++) {
                StepIr st = sec.steps().get(t);
                PatternPlan.StepPlan sp = p.sections().get(s).get(t);
                out.add("### " + st.titleSrc() + "  [" + sp.startSts() + " → " + sp.endSts() + "]");
                for (int r = 0; r < sp.rounds().size(); r++) {
                    RoundPlan rp = sp.rounds().get(r);
                    out.add("  " + (r + 1) + ". " + InstructionRenderer.render(rp.segments(), g) + "  ⇒ " + rp.stsAfter());
                }
            }
        }
        return out;
    }
}
