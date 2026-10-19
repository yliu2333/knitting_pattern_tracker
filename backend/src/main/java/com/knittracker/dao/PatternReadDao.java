package com.knittracker.dao;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knittracker.ir.PatternIr;
import com.knittracker.ir.RowIr;
import com.knittracker.ir.SectionIr;
import com.knittracker.ir.SegmentIr;
import com.knittracker.ir.StepIr;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Rebuilds the IR of a stored pattern. Runs under UserScope, so another user's pattern reads as absent. */
@Repository
public class PatternReadDao {

    /** IR plus the DB ids of its steps, in the same section/step order. */
    public record StoredPattern(UUID id, PatternIr ir, List<List<UUID>> stepIds) {}

    public record PatternSummary(UUID id, String title, String sourceLang, String createdAt) {}

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public PatternReadDao(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public List<PatternSummary> list() {
        return jdbc.sql("SELECT id, title, source_lang, created_at::text FROM pattern ORDER BY created_at DESC")
                .query((rs, i) -> new PatternSummary(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4)))
                .list();
    }

    public Optional<UUID> findByHash(String fileHash, String parserVersion) {
        return jdbc.sql("SELECT id FROM pattern WHERE file_hash = ? AND parser_version = ?")
                .params(fileHash, parserVersion).query(UUID.class).optional();
    }

    public Optional<StoredPattern> load(UUID patternId) {
        record Header(String title, String craft, String sourceType, String lang, String gauge) {}
        Optional<Header> header = jdbc.sql("""
                SELECT title, craft, source_type, source_lang, original_gauge::text FROM pattern WHERE id = ?""")
                .param(patternId)
                .query((rs, i) -> new Header(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5)))
                .optional();
        if (header.isEmpty()) return Optional.empty();

        Map<UUID, List<SegmentRow>> segmentsByRow = new HashMap<>();
        jdbc.sql("""
                SELECT g.id, g.step_row_id, g.parent_id, g.code, g.count, g.until, g.stop_before, g.marker,
                       g.attrs::text, g.raw
                FROM segment g
                JOIN step_row r ON r.id = g.step_row_id
                JOIN step t ON t.id = r.step_id
                JOIN section s ON s.id = t.section_id
                WHERE s.pattern_id = ?
                ORDER BY g.step_row_id, g.ord""")
                .param(patternId)
                .query((rs, i) -> segmentsByRow.computeIfAbsent(rs.getObject(2, UUID.class), k -> new ArrayList<>())
                        .add(segmentRow(rs)))
                .list();

        Map<UUID, List<RowIr>> rowsByStep = new HashMap<>();
        jdbc.sql("""
                SELECT r.id, r.step_id, r.side, r.name_key, r.name_src
                FROM step_row r
                JOIN step t ON t.id = r.step_id
                JOIN section s ON s.id = t.section_id
                WHERE s.pattern_id = ?
                ORDER BY r.step_id, r.ord""")
                .param(patternId)
                .query((rs, i) -> {
                    UUID rowId = rs.getObject(1, UUID.class);
                    RowIr row = new RowIr(enumOrNull(RowIr.Side.class, rs.getString(3)), rs.getString(4),
                            rs.getString(5), tree(segmentsByRow.getOrDefault(rowId, List.of()), null));
                    return rowsByStep.computeIfAbsent(rs.getObject(2, UUID.class), k -> new ArrayList<>()).add(row);
                })
                .list();

        Map<UUID, SectionBuilder> sections = new LinkedHashMap<>();
        jdbc.sql("SELECT id, name_key, name_src FROM section WHERE pattern_id = ? ORDER BY ord")
                .param(patternId)
                .query((rs, i) -> sections.put(rs.getObject(1, UUID.class),
                        new SectionBuilder(rs.getString(2), rs.getString(3))))
                .list();
        jdbc.sql("""
                SELECT t.id, t.section_id, t.kind, t.unit, t.title_key, t.title_src, t.note_src, t.needle_mm,
                       t.total, t.group_count, t.rows_per_group, t.target::text, t.structure::text
                FROM step t JOIN section s ON s.id = t.section_id
                WHERE s.pattern_id = ?
                ORDER BY s.ord, t.ord""")
                .param(patternId)
                .query((rs, i) -> {
                    UUID stepId = rs.getObject(1, UUID.class);
                    StepIr step = new StepIr(
                            StepIr.Kind.valueOf(rs.getString(3)), enumOrNull(StepIr.Unit.class, rs.getString(4)),
                            rs.getString(5), rs.getString(6), rs.getString(7),
                            rs.getObject(8) == null ? null : rs.getDouble(8),
                            (Integer) rs.getObject(9), (Integer) rs.getObject(10), (Integer) rs.getObject(11),
                            json(rs.getString(12)), json(rs.getString(13)),
                            rowsByStep.getOrDefault(stepId, List.of()));
                    SectionBuilder sb = sections.get(rs.getObject(2, UUID.class));
                    sb.steps.add(step);
                    return sb.ids.add(stepId);
                })
                .list();

        Header h = header.get();
        PatternIr ir = new PatternIr(1, h.title(), PatternIr.Craft.valueOf(h.craft()),
                PatternIr.SourceType.valueOf(h.sourceType()), h.lang(),
                h.gauge() == null ? null : read(h.gauge(), PatternIr.Gauge.class),
                sections.values().stream().map(b -> new SectionIr(b.nameKey, b.nameSrc, List.copyOf(b.steps))).toList());
        return Optional.of(new StoredPattern(patternId, ir,
                sections.values().stream().map(b -> List.copyOf(b.ids)).toList()));
    }

    private record SegmentRow(UUID id, UUID parentId, SegmentIr leaf) {}

    private static final class SectionBuilder {
        final String nameKey;
        final String nameSrc;
        final List<StepIr> steps = new ArrayList<>();
        final List<UUID> ids = new ArrayList<>();

        SectionBuilder(String nameKey, String nameSrc) {
            this.nameKey = nameKey;
            this.nameSrc = nameSrc;
        }
    }

    private SegmentRow segmentRow(ResultSet rs) throws SQLException {
        SegmentIr leaf = new SegmentIr(rs.getString(4), (Integer) rs.getObject(5),
                enumOrNull(SegmentIr.Until.class, rs.getString(6)), rs.getInt(7) == 0 ? null : rs.getInt(7),
                rs.getString(8), json(rs.getString(9)), rs.getString(10), null);
        return new SegmentRow(rs.getObject(1, UUID.class), rs.getObject(3, UUID.class), leaf);
    }

    /** Rows arrive ordered by ord; children are attached under their REPEAT parent. */
    private static List<SegmentIr> tree(List<SegmentRow> rows, UUID parent) {
        List<SegmentIr> out = new ArrayList<>();
        for (SegmentRow r : rows) {
            if (!java.util.Objects.equals(r.parentId(), parent)) continue;
            List<SegmentIr> children = tree(rows, r.id());
            SegmentIr l = r.leaf();
            out.add(new SegmentIr(l.code(), l.count(), l.until(), l.stopBefore(), l.marker(), l.attrs(), l.raw(),
                    children.isEmpty() ? null : children));
        }
        return out;
    }

    private static <E extends Enum<E>> E enumOrNull(Class<E> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }

    private JsonNode json(String s) {
        return s == null ? null : read(s, JsonNode.class);
    }

    private <T> T read(String s, Class<T> type) {
        try {
            return mapper.readValue(s, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
