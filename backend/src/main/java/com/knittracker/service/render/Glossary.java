package com.knittracker.service.render;

import java.util.Map;

/** Render templates for one language, keyed by code (from technique_i18n). */
public record Glossary(String lang, Map<String, String> templates) {

    public static final String SEPARATOR = "SEP";

    public String template(String key) {
        return templates.get(key);
    }

    public String separator() {
        return templates.getOrDefault(SEPARATOR, ", ");
    }
}
