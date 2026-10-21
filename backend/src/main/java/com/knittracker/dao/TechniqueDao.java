package com.knittracker.dao;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knittracker.service.technique.LearnedAlias;
import com.knittracker.service.technique.Technique;
import com.knittracker.service.technique.TechniqueLibrary;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Technique library from the DB: global (built-in + promoted) plus the caller's learned techniques.
 * Must run inside UserScope: user_technique* are RLS-scoped, so one user's learned names never leak to another.
 */
@Repository
public class TechniqueDao {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public TechniqueDao(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public TechniqueLibrary load() {
        Map<String, Map<String, String>> templates = new HashMap<>();
        jdbc.sql("SELECT code, lang, template FROM technique_i18n")
                .query((rs, i) -> templates.computeIfAbsent(rs.getString(1), k -> new HashMap<>())
                        .put(rs.getString(2), rs.getString(3)))
                .list();
        Map<String, Map<String, List<String>>> aliases = new HashMap<>();
        jdbc.sql("SELECT code, coalesce(lang, 'en'), alias FROM technique_alias")
                .query((rs, i) -> aliases.computeIfAbsent(rs.getString(1), k -> new LinkedHashMap<>())
                        .computeIfAbsent(rs.getString(2), k -> new ArrayList<>()).add(rs.getString(3)))
                .list();
        List<Technique> global = jdbc.sql("SELECT code, kind, def::text, names::text, origin FROM technique ORDER BY origin, code")
                .query((rs, i) -> new Technique(rs.getString(1), Technique.Kind.valueOf(rs.getString(2)),
                        json(rs.getString(3)), map(rs.getString(4)),
                        Map.copyOf(templates.getOrDefault(rs.getString(1), Map.of())),
                        Map.copyOf(aliases.getOrDefault(rs.getString(1), Map.of())),
                        Technique.Origin.valueOf(rs.getString(5))))
                .list();
        Map<String, Map<String, String>> renderKeys = new HashMap<>();
        templates.forEach((code, byLang) -> {
            if (global.stream().noneMatch(t -> t.code().equals(code))) {
                byLang.forEach((lang, tpl) -> renderKeys.computeIfAbsent(lang, k -> new HashMap<>()).put(code, tpl));
            }
        });

        Map<String, Map<String, List<String>>> personalAliases = new HashMap<>();
        List<LearnedAlias> aliasesToOthers = new ArrayList<>();
        jdbc.sql("SELECT code, coalesce(lang, 'en'), alias FROM user_technique_alias")
                .query((rs, i) -> {
                    aliasesToOthers.add(new LearnedAlias(rs.getString(3), rs.getString(2), rs.getString(1)));
                    return personalAliases.computeIfAbsent(rs.getString(1), k -> new LinkedHashMap<>())
                            .computeIfAbsent(rs.getString(2), k -> new ArrayList<>()).add(rs.getString(3));
                })
                .list();
        List<Technique> personal = jdbc.sql("SELECT code, kind, def::text, names::text, templates::text FROM user_technique")
                .query((rs, i) -> new Technique(rs.getString(1), Technique.Kind.valueOf(rs.getString(2)),
                        json(rs.getString(3)), map(rs.getString(4)), map(rs.getString(5)),
                        Map.copyOf(personalAliases.getOrDefault(rs.getString(1), Map.of())), Technique.Origin.PERSONAL))
                .list();
        return TechniqueLibrary.of(global, Map.copyOf(renderKeys)).with(personal).withAliases(aliasesToOthers);
    }

    /** Stores what a parse taught the system for this user. Codes and names already present are left alone. */
    public void saveLearned(UUID userId, List<Technique> learned, List<LearnedAlias> aliases, boolean verified) {
        for (Technique t : learned) {
            jdbc.sql("""
                    INSERT INTO user_technique (user_id, code, kind, def, signature, names, templates, verified)
                    VALUES (?, ?, ?, CAST(? AS jsonb), ?, CAST(? AS jsonb), CAST(? AS jsonb), ?)
                    ON CONFLICT DO NOTHING""")
                    .params(userId, t.code(), t.kind().name(), t.def() == null ? null : t.def().toString(),
                            t.signature(), write(t.names()), write(t.templates()), verified)
                    .update();
            t.aliases().forEach((lang, list) -> list.forEach(a -> addAlias(userId, a, lang, t.code())));
        }
        aliases.forEach(a -> addAlias(userId, a.alias(), a.lang(), a.code()));
    }

    /** Learned techniques used by a pattern whose stated counts all matched. */
    public void markVerified(List<String> codes) {
        if (codes.isEmpty()) return;
        jdbc.sql("UPDATE user_technique SET verified = true WHERE code IN (:codes)").param("codes", codes).update();
    }

    /** Promotes learned techniques/aliases that two users agree on; returns the newly global codes. */
    public List<String> promote() {
        return jdbc.sql("SELECT technique_promote()").query(String.class).list();
    }

    private void addAlias(UUID userId, String alias, String lang, String code) {
        String norm = com.knittracker.service.technique.AliasNorm.of(alias);
        if (norm.isEmpty()) return;
        jdbc.sql("""
                INSERT INTO user_technique_alias (user_id, alias_norm, alias, lang, code) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING""")
                .params(userId, norm, alias, lang, code)
                .update();
    }

    private JsonNode json(String s) {
        try {
            return s == null ? null : mapper.readTree(s);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> Map<String, T> map(String s) {
        try {
            return s == null ? Map.of() : mapper.readValue(s, new TypeReference<Map<String, T>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String write(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
