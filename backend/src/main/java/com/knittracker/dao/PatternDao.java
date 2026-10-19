package com.knittracker.dao;

import com.fasterxml.jackson.databind.JsonNode;
import com.knittracker.ir.PatternIr;
import com.knittracker.ir.RowIr;
import com.knittracker.ir.SegmentIr;
import com.knittracker.ir.StepIr;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Inserts IR rows. Must run inside {@link com.knittracker.security.UserScope}; RLS rejects any other user_id. */
@Repository
public class PatternDao {

    private final JdbcClient jdbc;

    public PatternDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID insertPattern(UUID userId, PatternIr p, String fileHash, String parserVersion, String gaugeJson) {
        return jdbc.sql("""
                INSERT INTO pattern (user_id, title, craft, source_type, source_lang, file_hash, parser_version, original_gauge)
                VALUES (:user, :title, :craft, :sourceType, :lang, :hash, :pv, CAST(:gauge AS jsonb))
                RETURNING id""")
                .param("user", userId).param("title", p.title()).param("craft", p.craft().name())
                .param("sourceType", p.sourceType().name()).param("lang", p.sourceLang())
                .param("hash", fileHash).param("pv", parserVersion).param("gauge", gaugeJson)
                .query(UUID.class).single();
    }

    public UUID insertSection(UUID userId, UUID patternId, int ord, String nameKey, String nameSrc) {
        return jdbc.sql("""
                INSERT INTO section (user_id, pattern_id, ord, name_key, name_src)
                VALUES (?, ?, ?, ?, ?) RETURNING id""")
                .params(userId, patternId, ord, nameKey, nameSrc)
                .query(UUID.class).single();
    }

    public UUID insertStep(UUID userId, UUID sectionId, int ord, StepIr s, int startSts, int endSts) {
        return jdbc.sql("""
                INSERT INTO step (user_id, section_id, ord, kind, unit, title_key, title_src, note_src, needle_mm,
                                  target, start_sts, end_sts, total, group_count, rows_per_group, structure)
                VALUES (:user, :section, :ord, :kind, :unit, :titleKey, :titleSrc, :note, :needle,
                        CAST(:target AS jsonb), :start, :end, :total, :groups, :perGroup, CAST(:structure AS jsonb))
                RETURNING id""")
                .param("user", userId).param("section", sectionId).param("ord", ord)
                .param("kind", s.kind().name()).param("unit", s.unit() == null ? null : s.unit().name())
                .param("titleKey", s.titleKey()).param("titleSrc", s.titleSrc()).param("note", s.noteSrc())
                .param("needle", s.needleMm()).param("target", json(s.target()))
                .param("start", startSts).param("end", endSts).param("total", s.total())
                .param("groups", s.groupCount()).param("perGroup", s.rowsPerGroup())
                .param("structure", json(s.structure()))
                .query(UUID.class).single();
    }

    public UUID insertRow(UUID userId, UUID stepId, int ord, RowIr r) {
        return jdbc.sql("""
                INSERT INTO step_row (user_id, step_id, ord, side, name_key, name_src)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING id""")
                .params(userId, stepId, ord, r.side() == null ? null : r.side().name(), r.nameKey(), r.nameSrc())
                .query(UUID.class).single();
    }

    public UUID insertSegment(UUID userId, UUID rowId, UUID parentId, int ord, SegmentIr g) {
        return jdbc.sql("""
                INSERT INTO segment (user_id, step_row_id, parent_id, ord, code, count, until, stop_before, marker, attrs, raw)
                VALUES (:user, :row, :parent, :ord, :code, :count, :until, :stopBefore, :marker, CAST(:attrs AS jsonb), :raw)
                RETURNING id""")
                .param("user", userId).param("row", rowId).param("parent", parentId).param("ord", ord)
                .param("code", g.code()).param("count", g.count())
                .param("until", g.until() == null ? null : g.until().name())
                .param("stopBefore", g.stopBefore() == null ? 0 : g.stopBefore())
                .param("marker", g.marker()).param("attrs", json(g.attrs())).param("raw", g.raw())
                .query(UUID.class).single();
    }

    private static String json(JsonNode node) {
        return node == null || node.isNull() ? null : node.toString();
    }
}
