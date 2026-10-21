package com.knittracker.service.parse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.knittracker.ir.PatternIr;
import com.knittracker.ir.SectionIr;
import com.knittracker.ir.StepIr;

/** The arithmetic the prompt forbids the LLM to do: REPEAT totals and inch → cm. */
final class IrNormalizer {

    private IrNormalizer() {}

    static PatternIr normalize(PatternIr ir, PatternIr.SourceType sourceType) {
        return new PatternIr(ir.irVersion(), ir.title(), PatternIr.Craft.KNIT, sourceType, ir.sourceLang(), ir.gauge(),
                ir.sections() == null ? null : ir.sections().stream()
                        .map(s -> new SectionIr(s.nameKey(), s.nameSrc(),
                                s.steps() == null ? null : s.steps().stream().map(IrNormalizer::step).toList()))
                        .toList());
    }

    private static StepIr step(StepIr s) {
        Integer total = s.total();
        if (s.kind() == StepIr.Kind.REPEAT && total == null && s.groupCount() != null && s.rowsPerGroup() != null) {
            total = s.groupCount() * s.rowsPerGroup();
        }
        return new StepIr(s.kind(), s.unit(), s.titleKey(), s.titleSrc(), s.noteSrc(), s.needleMm(), total,
                s.groupCount(), s.rowsPerGroup(), target(s.target()), s.structure(), s.rows(), s.statedEndSts());
    }

    /** {lengthIn: 6} → adds lengthCm 15.0 (rounded to 0.5 cm). */
    private static JsonNode target(JsonNode t) {
        if (t == null || !t.isObject() || !t.has("lengthIn") || t.has("lengthCm")) return t;
        ObjectNode copy = ((ObjectNode) t).deepCopy();
        copy.put("lengthCm", Math.round(t.get("lengthIn").asDouble() * 2.54 * 2) / 2.0);
        return copy;
    }
}
