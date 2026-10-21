package com.knittracker.service.render;

import java.util.Map;

/** Render templates for one language, keyed by code (from technique_i18n). */
public record Glossary(String lang, Map<String, String> templates) {

    public static final String SEPARATOR = "SEP";

    /** Templates for every technique in a user's library (built-in, promoted and their own learned ones). */
    public static Glossary from(com.knittracker.service.technique.TechniqueLibrary lib, String lang) {
        java.util.Map<String, String> m = new java.util.HashMap<>(lib.renderKeys().getOrDefault(lang, java.util.Map.of()));
        for (var t : lib.all()) {
            String tpl = lib.template(t.code(), lang);
            if (tpl != null) m.put(t.code(), tpl);
        }
        return new Glossary(lang, java.util.Map.copyOf(m));
    }

    public String template(String key) {
        return templates.get(key);
    }

    public String separator() {
        return templates.getOrDefault(SEPARATOR, ", ");
    }
}
