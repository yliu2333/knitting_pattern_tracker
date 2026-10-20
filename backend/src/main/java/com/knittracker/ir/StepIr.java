package com.knittracker.ir;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * One trackable step. {@code rows} cycle: round i uses rows.get(i % rows.size()).
 * {@code total == null} means open-ended (knit until {@link #target}).
 */
public record StepIr(
        Kind kind,
        Unit unit,
        String titleKey,
        String titleSrc,
        String noteSrc,
        Double needleMm,
        Integer total,
        Integer groupCount,
        Integer rowsPerGroup,
        JsonNode target,
        JsonNode structure,
        List<RowIr> rows) {

    public enum Kind { ROWS, REPEAT, CHECKPOINT }

    public enum Unit { ROW, ROUND }
}
