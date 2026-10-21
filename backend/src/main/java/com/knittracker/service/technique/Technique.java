package com.knittracker.service.technique;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/**
 * One technique in the library. {@code def} says what it does to stitches (for STITCH: consumes/produces plus
 * descriptors; for MACRO: the steps it expands to). Names and templates are per language; aliases are every
 * other name the technique goes by, per source language.
 */
public record Technique(
        String code,
        Kind kind,
        JsonNode def,
        Map<String, String> names,
        Map<String, String> templates,
        Map<String, List<String>> aliases,
        Origin origin) {

    /** PRIMITIVE = Java semantics (not learnable); the rest are data the simulator interprets. */
    public enum Kind { PRIMITIVE, STITCH, BINDOFF, FINISHING, MACRO }

    /** BUILTIN = shipped; GLOBAL = learned and promoted; PERSONAL = learned by one user, not yet promoted. */
    public enum Origin { BUILTIN, GLOBAL, PERSONAL }

    public int consumes() {
        return def == null ? 0 : def.path("consumes").asInt(0);
    }

    public int produces() {
        return def == null ? 0 : def.path("produces").asInt(0);
    }

    public String signature() {
        return Signature.of(kind, def);
    }
}
