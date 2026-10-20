package com.knittracker.service.count;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * A segment with every count made literal. {@code count} is stitches worked for K/P-style codes,
 * times performed for K2TOG/M1L-style codes, and 0 for codes without a count.
 * A REPEAT is kept only when all its iterations resolved identically; otherwise it is unrolled.
 */
public record ResolvedSegment(
        String code,
        int count,
        String marker,
        JsonNode attrs,
        String raw,
        int times,
        List<ResolvedSegment> children) {

    public static ResolvedSegment of(String code, int count, String marker, JsonNode attrs, String raw) {
        return new ResolvedSegment(code, count, marker, attrs, raw, 0, List.of());
    }

    public static ResolvedSegment repeat(int times, List<ResolvedSegment> children) {
        return new ResolvedSegment("REPEAT", 0, null, null, null, times, List.copyOf(children));
    }
}
