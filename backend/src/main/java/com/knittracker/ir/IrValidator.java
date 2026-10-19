package com.knittracker.ir;

import java.util.ArrayList;
import java.util.List;

/** Structural checks on parsed IR. Returns human-readable problems; empty list = valid. */
public final class IrValidator {

    private IrValidator() {}

    public static List<String> validate(PatternIr p) {
        List<String> errors = new ArrayList<>();
        if (p.irVersion() != 1) errors.add("irVersion must be 1");
        if (isBlank(p.title())) errors.add("title is required");
        if (p.craft() == null) errors.add("craft is required");
        if (p.sourceType() == null) errors.add("sourceType is required");
        if (isBlank(p.sourceLang())) errors.add("sourceLang is required");
        if (p.sections() == null || p.sections().isEmpty()) {
            errors.add("at least one section is required");
            return errors;
        }
        for (int s = 0; s < p.sections().size(); s++) {
            SectionIr sec = p.sections().get(s);
            String sp = "sections[" + s + "]";
            if (isBlank(sec.nameKey()) && isBlank(sec.nameSrc())) errors.add(sp + ": nameKey or nameSrc required");
            if (sec.steps() == null || sec.steps().isEmpty()) {
                errors.add(sp + ": no steps");
                continue;
            }
            for (int t = 0; t < sec.steps().size(); t++) {
                validateStep(sec.steps().get(t), sp + ".steps[" + t + "]", errors);
            }
        }
        return errors;
    }

    private static void validateStep(StepIr st, String path, List<String> errors) {
        if (st.kind() == null) errors.add(path + ": kind is required");
        if (isBlank(st.titleKey()) && isBlank(st.titleSrc())) errors.add(path + ": titleKey or titleSrc required");
        if (st.kind() != StepIr.Kind.CHECKPOINT && st.unit() == null) errors.add(path + ": unit required for " + st.kind());
        if (st.total() != null && st.total() < 1) errors.add(path + ": total must be >= 1");
        if (st.total() == null && st.kind() != StepIr.Kind.CHECKPOINT && st.target() == null) {
            errors.add(path + ": open-ended step needs a target");
        }
        if (st.kind() == StepIr.Kind.REPEAT) {
            if (st.groupCount() == null || st.rowsPerGroup() == null) {
                errors.add(path + ": REPEAT needs groupCount and rowsPerGroup");
            } else if (st.total() != null && st.groupCount() * st.rowsPerGroup() != st.total()) {
                errors.add(path + ": groupCount * rowsPerGroup != total");
            }
        }
        if (st.rows() == null || st.rows().isEmpty()) {
            errors.add(path + ": at least one row required");
            return;
        }
        for (int r = 0; r < st.rows().size(); r++) {
            RowIr row = st.rows().get(r);
            if (row.segments() == null || row.segments().isEmpty()) errors.add(path + ".rows[" + r + "]: no segments");
            else validateSegments(row.segments(), path + ".rows[" + r + "]", errors);
        }
    }

    private static void validateSegments(List<SegmentIr> segs, String path, List<String> errors) {
        for (int i = 0; i < segs.size(); i++) {
            SegmentIr g = segs.get(i);
            String sp = path + ".segments[" + i + "]";
            if (isBlank(g.code())) {
                errors.add(sp + ": code is required");
                continue;
            }
            if (g.count() != null && g.until() != null) errors.add(sp + ": count and until are exclusive");
            if (g.count() != null && g.count() < 0) errors.add(sp + ": count must be >= 0");
            if (SegmentIr.UNKNOWN.equals(g.code()) && isBlank(g.raw())) errors.add(sp + ": UNKNOWN needs raw");
            boolean repeat = SegmentIr.REPEAT.equals(g.code());
            if (repeat && g.childrenOrEmpty().isEmpty()) errors.add(sp + ": REPEAT needs children");
            if (repeat && g.count() == null && g.until() == null) errors.add(sp + ": REPEAT needs count or until");
            if (!repeat && !g.childrenOrEmpty().isEmpty()) errors.add(sp + ": only REPEAT may have children");
            validateSegments(g.childrenOrEmpty(), sp + ".children", errors);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
