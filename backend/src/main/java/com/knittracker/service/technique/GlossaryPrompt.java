package com.knittracker.service.technique;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** The library as prompt text: one line per technique with its kind, structure and every known name. */
public final class GlossaryPrompt {

    private GlossaryPrompt() {}

    public static String of(TechniqueLibrary lib) {
        StringBuilder b = new StringBuilder("Format: CODE [kind; structure] — en names | ja names | zh names\n");
        for (Technique t : lib.all()) {
            if (t.code().equals("UNKNOWN")) continue;
            b.append(t.code()).append(" [").append(t.kind());
            if (t.def() != null && !t.def().isEmpty()) b.append("; ").append(t.def());
            b.append("] — ").append(names(t, "en")).append(" | ").append(names(t, "ja")).append(" | ")
                    .append(names(t, "zh")).append('\n');
        }
        return b.toString();
    }

    private static String names(Technique t, String lang) {
        java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>();
        if (t.names() != null && t.names().get(lang) != null) all.add(t.names().get(lang));
        Map<String, List<String>> al = t.aliases();
        if (al != null && al.get(lang) != null) all.addAll(al.get(lang));
        return all.stream().collect(Collectors.joining(", "));
    }
}
