package com.knittracker.ir;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * One action, e.g. {code:K, count:17} or {code:K, until:MARKER, stopBefore:1}.
 * A REPEAT segment repeats {@code children} {@code count} times, or until {@code until}.
 * Unrecognised source terms come in as code UNKNOWN with the original text in {@code raw}.
 */
public record SegmentIr(
        String code,
        Integer count,
        Until until,
        Integer stopBefore,
        String marker,
        JsonNode attrs,
        String raw,
        List<SegmentIr> children) {

    public static final String REPEAT = "REPEAT";
    public static final String UNKNOWN = "UNKNOWN";

    /** Where an open-count segment stops: next marker, beginning of round, short-row gap, end of row. */
    public enum Until { MARKER, BOR, GAP, END }

    public List<SegmentIr> childrenOrEmpty() {
        return children == null ? List.of() : children;
    }
}
