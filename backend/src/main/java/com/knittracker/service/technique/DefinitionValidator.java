package com.knittracker.service.technique;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Checks an LLM-proposed technique before it can enter a library. Returns problems; empty = acceptable. */
final class DefinitionValidator {

    static final Set<String> WORKED = Set.of("KNIT", "PURL", "SLIP", "NONE");
    static final Set<String> LEAN = Set.of("LEFT", "RIGHT", "CENTER", "NONE");
    static final Set<String> YARN = Set.of("FRONT", "BACK");
    static final Set<String> SOURCE = Set.of("LIVE", "BAR", "YO", "BELOW", "CAST_ON", "EDGE");
    static final Set<String> STITCH_KEYS = Set.of("consumes", "produces", "worked", "lean", "tbl", "yarn", "source",
            "method", "cross");

    private DefinitionValidator() {}

    static List<String> validate(Technique t, TechniqueLibrary lib) {
        List<String> e = new ArrayList<>();
        if (t.code() == null || !t.code().matches("[A-Z][A-Z0-9_]{1,29}")) e.add("code must be UPPER_SNAKE_CASE");
        if (t.kind() == Technique.Kind.PRIMITIVE) e.add("PRIMITIVE techniques cannot be learned");
        for (String lang : List.of("zh", "en")) {
            if (t.names() == null || blank(t.names().get(lang))) e.add("names." + lang + " missing");
            String tpl = t.templates() == null ? null : t.templates().get(lang);
            if (blank(tpl)) e.add("templates." + lang + " missing");
            else if (tpl.replace("{n}", "").contains("{")) e.add("templates." + lang + " may only use {n}");
        }
        JsonNode d = t.def();
        switch (t.kind()) {
            case STITCH -> {
                if (d == null || !d.isObject()) {
                    e.add("STITCH needs def");
                    break;
                }
                d.fieldNames().forEachRemaining(f -> {
                    if (!STITCH_KEYS.contains(f)) e.add("unknown def field " + f);
                });
                int c = d.path("consumes").asInt(-1);
                int p = d.path("produces").asInt(-1);
                if (!d.path("consumes").isInt() || c < 0 || c > 12) e.add("def.consumes must be 0..12");
                if (!d.path("produces").isInt() || p < 0 || p > 12) e.add("def.produces must be 0..12");
                if (c == 0 && p == 0) e.add("a STITCH must consume or produce something");
                enumField(d, "worked", WORKED, e);
                enumField(d, "lean", LEAN, e);
                enumField(d, "yarn", YARN, e);
                enumField(d, "source", SOURCE, e);
                if (d.has("method") && !d.get("method").asText().matches("[A-Z][A-Z0-9_]{1,29}")) {
                    e.add("def.method must be UPPER_SNAKE_CASE");
                }
                if (d.has("cross")) {
                    JsonNode x = d.get("cross");
                    if (!x.path("front").isInt() || !x.path("back").isInt() || !Set.of("LEFT", "RIGHT").contains(x.path("dir").asText())) {
                        e.add("def.cross needs front, back and dir LEFT|RIGHT");
                    } else if (x.get("front").asInt() + x.get("back").asInt() != c || c != p) {
                        e.add("a cable crosses front + back = consumes = produces stitches");
                    }
                }
            }
            case BINDOFF -> {
                if (d == null || !d.path("method").asText("").matches("[A-Z][A-Z0-9_]{1,29}")) e.add("BINDOFF needs def.method");
            }
            case MACRO -> {
                if (d == null || !d.path("steps").isArray() || d.get("steps").isEmpty()) {
                    e.add("MACRO needs def.steps");
                    break;
                }
                for (JsonNode s : d.get("steps")) {
                    String code = s.path("code").asText("");
                    if (lib.byCode(code).isEmpty() || code.equals("REPEAT") || code.equals("UNKNOWN")) {
                        e.add("MACRO step uses unknown or disallowed code " + code);
                    }
                }
            }
            case FINISHING, PRIMITIVE -> { }
        }
        return e;
    }

    private static void enumField(JsonNode d, String f, Set<String> allowed, List<String> e) {
        if (d.has(f) && !allowed.contains(d.get(f).asText())) e.add("def." + f + " must be one of " + allowed);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
