package com.knittracker.service.render;

import com.knittracker.service.count.ResolvedSegment;
import com.knittracker.service.count.StitchSimulator;
import java.util.List;
import java.util.stream.Collectors;

/** Resolved segments + glossary → one line of instructions. Deterministic, no LLM. */
public final class InstructionRenderer {

    private InstructionRenderer() {}

    public static String render(List<ResolvedSegment> segments, Glossary g) {
        return segments.stream().map(s -> render(s, g)).collect(Collectors.joining(g.separator()));
    }

    static String render(ResolvedSegment s, Glossary g) {
        if (s.code().equals("REPEAT")) {
            return template(g, "REPEAT")
                    .replace("{items}", render(s.children(), g))
                    .replace("{times}", Integer.toString(s.times()));
        }
        String key = s.code();
        if ((key.equals("PM") || key.equals("SM")) && StitchSimulator.BOR.equals(s.marker())) key += "_BOR";
        String t = template(g, key);
        String text = t.replace("{n}", Integer.toString(s.count()))
                .replace("{raw}", s.raw() == null ? "" : s.raw());
        if (!t.contains("{n}") && s.count() > 1) text += " × " + s.count();
        if (s.attrs() != null && "SADDLE".equals(s.attrs().path("tag").asText())) text += template(g, "TAG_SADDLE");
        return text;
    }

    private static String template(Glossary g, String key) {
        String t = g.template(key);
        return t != null ? t : "⟨" + key + " {n}⟩";
    }
}
