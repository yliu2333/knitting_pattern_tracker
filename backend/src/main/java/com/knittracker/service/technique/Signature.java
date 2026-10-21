package com.knittracker.service.technique;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.StringJoiner;

/**
 * Structural identity of a technique. Two techniques with the same signature ARE the same technique under
 * different names, so a newly learned one with an existing signature becomes an alias instead of a duplicate.
 * Only the closed descriptor set below counts; anything else in a definition is ignored.
 */
public final class Signature {

    static final List<String> STITCH_FIELDS =
            List.of("consumes", "produces", "worked", "lean", "tbl", "yarn", "source", "method", "cross");

    private Signature() {}

    /** Null for kinds without a structural identity (FINISHING, PRIMITIVE): those dedup by name only. */
    public static String of(Technique.Kind kind, JsonNode def) {
        return switch (kind) {
            case STITCH -> {
                StringJoiner j = new StringJoiner("|", "STITCH|", "");
                for (String f : STITCH_FIELDS) {
                    JsonNode v = def == null ? null : def.get(f);
                    if (v == null || v.isNull() || (v.isBoolean() && !v.asBoolean())) continue;
                    j.add(f + "=" + (v.isTextual() ? v.asText().toUpperCase() : v.toString()));
                }
                yield j.toString();
            }
            case BINDOFF -> "BINDOFF|method=" + (def == null ? "" : def.path("method").asText("").toUpperCase());
            case MACRO -> "MACRO|" + (def == null ? "" : def.path("steps").toString());
            case FINISHING, PRIMITIVE -> null;
        };
    }
}
