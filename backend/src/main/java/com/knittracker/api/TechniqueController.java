package com.knittracker.api;

import com.knittracker.dao.TechniqueDao;
import com.knittracker.security.UserScope;
import com.knittracker.service.technique.AliasNorm;
import com.knittracker.service.technique.Technique;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The caller's technique library: one entry per technique, its name in {@code lang} and every other name ("也叫"). */
@RestController
@RequestMapping("/api/techniques")
public class TechniqueController {

    /** {@code learned}: taught by a pattern (PERSONAL = only this user so far; GLOBAL = confirmed by several users). */
    public record TechniqueView(String code, String kind, String name, List<String> alsoKnownAs, String origin) {}

    private final TechniqueDao techniques;
    private final UserScope scope;

    public TechniqueController(TechniqueDao techniques, UserScope scope) {
        this.techniques = techniques;
        this.scope = scope;
    }

    @GetMapping
    public List<TechniqueView> list(@RequestParam(defaultValue = "zh") String lang) {
        return scope.asCurrentUser(() -> techniques.load().all().stream()
                .filter(t -> !t.code().equals("UNKNOWN"))
                .map(t -> view(t, lang))
                .toList());
    }

    static TechniqueView view(Technique t, String lang) {
        String name = t.names().getOrDefault(lang, t.names().getOrDefault("en", t.code()));
        Map<String, String> others = new LinkedHashMap<>();   // normalized → as written, so spellings collapse
        t.names().values().forEach(n -> others.putIfAbsent(AliasNorm.of(n), n));
        t.aliases().values().forEach(list -> list.forEach(a -> others.putIfAbsent(AliasNorm.of(a), a)));
        others.remove(AliasNorm.of(name));
        return new TechniqueView(t.code(), t.kind().name(), name, List.copyOf(others.values()), t.origin().name());
    }
}
