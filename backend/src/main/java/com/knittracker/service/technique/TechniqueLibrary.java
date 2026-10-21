package com.knittracker.service.technique;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable view of the techniques one user can use: built-in + promoted + their own learned ones.
 * Every alias resolves to exactly one code, and every structural signature belongs to exactly one code.
 */
public final class TechniqueLibrary {

    public static final String BUILTIN_RESOURCE = "techniques/builtin.json";

    private static volatile TechniqueLibrary builtin;

    private final Map<String, Technique> byCode;
    private final Map<String, String> codeByAlias;
    private final Map<String, String> codeBySignature;
    private final Map<String, Map<String, String>> renderKeys;

    private TechniqueLibrary(Map<String, Technique> byCode, Map<String, String> codeByAlias,
                             Map<String, String> codeBySignature, Map<String, Map<String, String>> renderKeys) {
        this.byCode = byCode;
        this.codeByAlias = codeByAlias;
        this.codeBySignature = codeBySignature;
        this.renderKeys = renderKeys;
    }

    public static TechniqueLibrary of(Collection<Technique> techniques, Map<String, Map<String, String>> renderKeys) {
        Map<String, Technique> byCode = new LinkedHashMap<>();
        Map<String, String> byAlias = new HashMap<>();
        Map<String, String> bySig = new HashMap<>();
        for (Technique t : techniques) {
            if (byCode.containsKey(t.code())) continue;          // earlier source (builtin/global) wins
            byCode.put(t.code(), t);
            byAlias.putIfAbsent(AliasNorm.of(t.code()), t.code());
            t.names().values().forEach(n -> byAlias.putIfAbsent(AliasNorm.of(n), t.code()));
            if (t.aliases() != null) {
                t.aliases().values().forEach(list -> list.forEach(a -> byAlias.putIfAbsent(AliasNorm.of(a), t.code())));
            }
            String sig = t.signature();
            if (sig != null) bySig.putIfAbsent(sig, t.code());
        }
        return new TechniqueLibrary(Map.copyOf(byCode), Map.copyOf(byAlias), Map.copyOf(bySig), renderKeys);
    }

    /** The shipped library (no DB needed): used by tests and as the base of every user's library. */
    public static TechniqueLibrary builtin() {
        TechniqueLibrary b = builtin;
        if (b == null) {
            synchronized (TechniqueLibrary.class) {
                if (builtin == null) builtin = loadBuiltin(new ObjectMapper());
                b = builtin;
            }
        }
        return b;
    }

    static TechniqueLibrary loadBuiltin(ObjectMapper mapper) {
        try (InputStream in = TechniqueLibrary.class.getClassLoader().getResourceAsStream(BUILTIN_RESOURCE)) {
            JsonNode root = mapper.readTree(in);
            List<Technique> list = new ArrayList<>();
            for (JsonNode n : root.get("techniques")) list.add(parse(mapper, n, Technique.Origin.BUILTIN));
            Map<String, Map<String, String>> render = new HashMap<>();
            root.path("renderKeys").fields().forEachRemaining(e -> {
                Map<String, String> m = new HashMap<>();
                e.getValue().fields().forEachRemaining(k -> m.put(k.getKey(), k.getValue().asText()));
                render.put(e.getKey(), Map.copyOf(m));
            });
            return of(list, Map.copyOf(render));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Technique parse(ObjectMapper mapper, JsonNode n, Technique.Origin origin) {
        return new Technique(n.get("code").asText(), Technique.Kind.valueOf(n.get("kind").asText()),
                n.get("def"),
                mapper.convertValue(n.path("names"), Map.class),
                mapper.convertValue(n.path("templates"), Map.class),
                n.has("aliases") ? mapper.convertValue(n.get("aliases"), Map.class) : Map.of(),
                origin);
    }

    /** This library plus more techniques (e.g. a user's learned ones); existing codes, aliases and signatures win. */
    public TechniqueLibrary with(Collection<Technique> more) {
        List<Technique> all = new ArrayList<>(byCode.values());
        all.addAll(more);
        return of(all, renderKeys);
    }

    /** Extra names (e.g. a user's own name for K2TOG); a name that already has an owner keeps it. */
    public TechniqueLibrary withAliases(Collection<LearnedAlias> aliases) {
        Map<String, String> byAlias = new HashMap<>(codeByAlias);
        for (LearnedAlias a : aliases) {
            if (byCode.containsKey(a.code())) byAlias.putIfAbsent(AliasNorm.of(a.alias()), a.code());
        }
        return new TechniqueLibrary(byCode, Map.copyOf(byAlias), codeBySignature, renderKeys);
    }

    public Map<String, Map<String, String>> renderKeys() {
        return renderKeys;
    }

    public Optional<Technique> byCode(String code) {
        return Optional.ofNullable(byCode.get(code));
    }

    public Optional<Technique> byAlias(String term) {
        return Optional.ofNullable(codeByAlias.get(AliasNorm.of(term))).map(byCode::get);
    }

    public Optional<Technique> bySignature(String signature) {
        return signature == null ? Optional.empty() : Optional.ofNullable(codeBySignature.get(signature)).map(byCode::get);
    }

    public Collection<Technique> all() {
        return byCode.values();
    }

    /** Render template for a code or render key (SEP, PM_BOR, …) in a language; null when missing. */
    public String template(String key, String lang) {
        Technique t = byCode.get(key);
        if (t != null && t.templates() != null && t.templates().containsKey(lang)) return t.templates().get(lang);
        Map<String, String> r = renderKeys.get(lang);
        return r == null ? null : r.get(key);
    }

    public boolean supportsLanguage(String lang) {
        return renderKeys.containsKey(lang);
    }
}
