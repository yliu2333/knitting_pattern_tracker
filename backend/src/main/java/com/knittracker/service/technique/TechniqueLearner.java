package com.knittracker.service.technique;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knittracker.ir.PatternIr;
import com.knittracker.ir.RowIr;
import com.knittracker.ir.SectionIr;
import com.knittracker.ir.SegmentIr;
import com.knittracker.ir.StepIr;
import com.knittracker.service.llm.LlmClient;
import com.knittracker.service.llm.LlmException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves every term a parse could not map to a code. Known aliases first (no LLM); the rest in ONE define call
 * per batch. Each answer is validated and de-duplicated before it is accepted:
 * <ol>
 *   <li>aliasOf an existing code → just a new name;</li>
 *   <li>a "new" technique whose structure (signature) or any of whose names already exists → becomes an alias of
 *       that existing technique, never a duplicate;</li>
 *   <li>otherwise a new personal technique.</li>
 * </ol>
 * Stateless: every call carries only the unknown terms, their sentences and the library listing.
 */
public class TechniqueLearner {

    public record Result(PatternIr ir, TechniqueLibrary library, List<Technique> learned, List<LearnedAlias> aliases,
                         List<String> unsupported) {}

    private final LlmClient llm;
    private final ObjectMapper mapper;
    private final String systemPrompt;

    public TechniqueLearner(LlmClient llm, ObjectMapper mapper) {
        this.llm = llm;
        this.mapper = mapper;
        this.systemPrompt = resource("llm/define-system.md");
    }

    public Result learn(PatternIr ir, TechniqueLibrary lib) {
        Map<String, String> context = new LinkedHashMap<>();          // term → sentence it appeared in
        collectUnknown(ir, lib, context);
        Map<String, String> resolved = new LinkedHashMap<>();         // term → code
        List<Technique> learned = new ArrayList<>();
        List<LearnedAlias> aliases = new ArrayList<>();
        List<String> unsupported = new ArrayList<>();
        String lang = ir.sourceLang();

        for (String term : List.copyOf(context.keySet())) {
            Optional<Technique> known = lib.byAlias(term);
            if (known.isPresent()) {
                resolved.put(term, known.get().code());
                context.remove(term);
            }
        }
        if (!context.isEmpty()) {
            JsonNode answers = define(context, lib, lang);
            for (JsonNode a : answers.path("terms")) {
                String term = a.path("term").asText();
                if (!context.containsKey(term) || resolved.containsKey(term)) continue;
                if (a.hasNonNull("aliasOf") && lib.byCode(a.get("aliasOf").asText()).isPresent()) {
                    alias(term, lang, a.get("aliasOf").asText(), resolved, aliases);
                } else if (a.has("technique")) {
                    accept(term, lang, a.get("technique"), lib, resolved, learned, aliases, unsupported);
                    lib = lib.with(learned.isEmpty() ? List.of() : List.of(learned.getLast()));
                } else {
                    unsupported.add(term + ": " + a.path("unsupported").asText("no definition returned"));
                }
            }
            context.keySet().stream().filter(t -> !resolved.containsKey(t) && unsupported.stream().noneMatch(u -> u.startsWith(t + ":")))
                    .forEach(t -> unsupported.add(t + ": no answer"));
        }
        return new Result(replace(ir, resolved), lib, learned, aliases, unsupported);
    }

    private void accept(String term, String lang, JsonNode node, TechniqueLibrary lib, Map<String, String> resolved,
                        List<Technique> learned, List<LearnedAlias> aliases, List<String> unsupported) {
        Technique t;
        try {
            t = TechniqueLibrary.parse(mapper, node, Technique.Origin.PERSONAL);
        } catch (RuntimeException e) {
            unsupported.add(term + ": definition is malformed");
            return;
        }
        List<String> problems = DefinitionValidator.validate(t, lib);
        if (!problems.isEmpty()) {
            unsupported.add(term + ": " + String.join("; ", problems));
            return;
        }
        // Same structure as an existing technique → it IS that technique.
        Optional<Technique> sameStructure = lib.bySignature(t.signature());
        if (sameStructure.isPresent()) {
            alias(term, lang, sameStructure.get().code(), resolved, aliases);
            return;
        }
        // A name of the proposal already belongs to a technique → that technique (names are unique).
        List<String> names = new ArrayList<>(t.names().values());
        t.aliases().values().forEach(names::addAll);
        for (String n : names) {
            Optional<Technique> owner = lib.byAlias(n);
            if (owner.isPresent()) {
                if (owner.get().signature() != null && t.signature() != null && !owner.get().signature().equals(t.signature())) {
                    unsupported.add(term + ": the name '" + n + "' already means " + owner.get().code() + " with a different structure");
                } else {
                    alias(term, lang, owner.get().code(), resolved, aliases);
                }
                return;
            }
        }
        String code = t.code();
        for (int i = 2; lib.byCode(code).isPresent(); i++) code = t.code() + "_" + i;
        Map<String, List<String>> al = new LinkedHashMap<>(t.aliases());
        List<String> forLang = new ArrayList<>(al.getOrDefault(lang, List.of()));
        if (forLang.stream().noneMatch(x -> AliasNorm.of(x).equals(AliasNorm.of(term)))) forLang.add(term);
        al.put(lang, forLang);
        Technique accepted = new Technique(code, t.kind(), t.def(), t.names(), t.templates(), al, Technique.Origin.PERSONAL);
        learned.add(accepted);
        resolved.put(term, code);
    }

    private static void alias(String term, String lang, String code, Map<String, String> resolved,
                              List<LearnedAlias> out) {
        resolved.put(term, code);
        out.add(new LearnedAlias(term, lang, code));
    }

    private JsonNode define(Map<String, String> context, TechniqueLibrary lib, String lang) {
        StringBuilder p = new StringBuilder("Source language: ").append(lang).append("\n\n# Library\n");
        p.append(GlossaryPrompt.of(lib)).append("\n# Unknown terms\n");
        context.forEach((term, sentence) -> p.append("- term: ").append(term).append("\n  sentence: ")
                .append(sentence == null ? "" : sentence).append('\n'));
        try {
            String raw = llm.completeJson(systemPrompt, p.toString(), List.of());
            return mapper.readTree(stripFences(raw));
        } catch (LlmException | IOException e) {
            return mapper.createObjectNode();
        }
    }

    // ---------- IR walking ----------

    private static void collectUnknown(PatternIr ir, TechniqueLibrary lib, Map<String, String> out) {
        for (SectionIr s : ir.sections()) {
            for (StepIr st : s.steps()) {
                for (RowIr r : st.rows()) collect(r.segments(), lib, out);
            }
        }
    }

    private static void collect(List<SegmentIr> segs, TechniqueLibrary lib, Map<String, String> out) {
        if (segs == null) return;
        for (SegmentIr g : segs) {
            String term = term(g, lib);
            if (term != null) out.putIfAbsent(term, g.attrs() == null ? null : g.attrs().path("context").asText(null));
            collect(g.children(), lib, out);
        }
    }

    /** The name to learn for a segment, or null when its code is already in the library. */
    private static String term(SegmentIr g, TechniqueLibrary lib) {
        if (SegmentIr.UNKNOWN.equals(g.code())) return g.raw() == null || g.raw().isBlank() ? null : g.raw().strip();
        return lib.byCode(g.code()).isPresent() ? null : g.code();
    }

    private static PatternIr replace(PatternIr ir, Map<String, String> resolved) {
        if (resolved.isEmpty()) return ir;
        return new PatternIr(ir.irVersion(), ir.title(), ir.craft(), ir.sourceType(), ir.sourceLang(), ir.gauge(),
                ir.sections().stream().map(s -> new SectionIr(s.nameKey(), s.nameSrc(), s.steps().stream()
                        .map(st -> new StepIr(st.kind(), st.unit(), st.titleKey(), st.titleSrc(), st.noteSrc(),
                                st.needleMm(), st.total(), st.groupCount(), st.rowsPerGroup(), st.target(),
                                st.structure(), st.rows().stream().map(r -> new RowIr(r.side(), r.nameKey(), r.nameSrc(),
                                        replace(r.segments(), resolved))).toList(), st.statedEndSts()))
                        .toList())).toList());
    }

    private static List<SegmentIr> replace(List<SegmentIr> segs, Map<String, String> resolved) {
        if (segs == null) return null;
        return segs.stream().map(g -> {
            String key = SegmentIr.UNKNOWN.equals(g.code()) ? (g.raw() == null ? null : g.raw().strip()) : g.code();
            String code = key == null ? null : resolved.get(key);
            return new SegmentIr(code != null ? code : g.code(), g.count(), g.until(), g.stopBefore(), g.marker(),
                    g.attrs(), g.raw(), replace(g.children(), resolved));
        }).toList();
    }

    static String stripFences(String raw) {
        String s = raw.strip();
        if (s.startsWith("```")) {
            s = s.substring(s.indexOf('\n') + 1);
            int end = s.lastIndexOf("```");
            if (end >= 0) s = s.substring(0, end);
        }
        return s.strip();
    }

    private static String resource(String path) {
        try (InputStream in = TechniqueLearner.class.getClassLoader().getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
