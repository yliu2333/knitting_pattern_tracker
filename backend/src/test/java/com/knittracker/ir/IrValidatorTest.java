package com.knittracker.ir;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class IrValidatorTest {

    private final PatternIr hat = Fixtures.read(Fixtures.PUBLIC_DIR.resolve("sample-hat.ir.json"));

    @Test
    void rejectsCountAndUntilTogether() {
        PatternIr bad = withFirstSegment(new SegmentIr("K", 3, SegmentIr.Until.END, null, null, null, null, null));
        assertThat(IrValidator.validate(bad)).anyMatch(e -> e.contains("count and until are exclusive"));
    }

    @Test
    void rejectsUnknownWithoutRaw() {
        PatternIr bad = withFirstSegment(new SegmentIr(SegmentIr.UNKNOWN, null, null, null, null, null, null, null));
        assertThat(IrValidator.validate(bad)).anyMatch(e -> e.contains("UNKNOWN needs raw"));
    }

    @Test
    void acceptsUnknownWithRaw() {
        PatternIr ok = withFirstSegment(new SegmentIr(SegmentIr.UNKNOWN, null, null, null, null, null, "kfbf", null));
        assertThat(IrValidator.validate(ok)).isEmpty();
    }

    @Test
    void rejectsOpenEndedStepWithoutTarget() {
        StepIr st = hat.sections().get(1).steps().get(0);
        StepIr noTarget = new StepIr(st.kind(), st.unit(), st.titleKey(), st.titleSrc(), st.noteSrc(), st.needleMm(),
                null, null, null, null, null, st.rows());
        List<SectionIr> sections = new ArrayList<>(hat.sections());
        sections.set(1, new SectionIr(null, "Body", List.of(noTarget)));
        assertThat(IrValidator.validate(copy(sections))).anyMatch(e -> e.contains("needs a target"));
    }

    private PatternIr withFirstSegment(SegmentIr seg) {
        StepIr st = hat.sections().get(0).steps().get(0);
        StepIr replaced = new StepIr(st.kind(), st.unit(), st.titleKey(), st.titleSrc(), st.noteSrc(), st.needleMm(),
                st.total(), st.groupCount(), st.rowsPerGroup(), st.target(), st.structure(),
                List.of(new RowIr(null, null, null, List.of(seg))));
        List<SectionIr> sections = new ArrayList<>(hat.sections());
        sections.set(0, new SectionIr(null, "Brim", List.of(replaced)));
        return copy(sections);
    }

    private PatternIr copy(List<SectionIr> sections) {
        return new PatternIr(hat.irVersion(), hat.title(), hat.craft(), hat.sourceType(), hat.sourceLang(),
                hat.gauge(), sections);
    }
}
