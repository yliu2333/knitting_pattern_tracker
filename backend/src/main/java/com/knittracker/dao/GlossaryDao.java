package com.knittracker.dao;

import com.knittracker.service.render.Glossary;
import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** technique_i18n is global (not user-owned), so no UserScope is needed. */
@Repository
public class GlossaryDao {

    private final JdbcClient jdbc;

    public GlossaryDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Glossary load(String lang) {
        Map<String, String> templates = new HashMap<>();
        jdbc.sql("SELECT code, template FROM technique_i18n WHERE lang = ?").param(lang)
                .query((rs, i) -> templates.put(rs.getString("code"), rs.getString("template"))).list();
        return new Glossary(lang, Map.copyOf(templates));
    }
}
