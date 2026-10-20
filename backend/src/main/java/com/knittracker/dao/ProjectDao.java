package com.knittracker.dao;

import com.knittracker.ir.StepIr;
import com.knittracker.service.progress.ProgressRules.StepMeta;
import com.knittracker.service.progress.StepState;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Projects, progress and undo history. All calls run under UserScope (RLS). */
@Repository
public class ProjectDao {

    public record ProjectRow(UUID id, UUID patternId, String title, String createdAt) {}

    public record EventRow(long id, String payload) {}

    private final JdbcClient jdbc;

    public ProjectDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID create(UUID userId, UUID patternId) {
        return jdbc.sql("INSERT INTO project (user_id, pattern_id) VALUES (?, ?) RETURNING id")
                .params(userId, patternId).query(UUID.class).single();
    }

    public List<ProjectRow> list() {
        return jdbc.sql("""
                SELECT p.id, p.pattern_id, t.title, p.created_at::text
                FROM project p JOIN pattern t ON t.id = p.pattern_id ORDER BY p.created_at DESC""")
                .query((rs, i) -> new ProjectRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getString(4)))
                .list();
    }

    /** Row-locks the project so concurrent taps from two devices apply one after the other. */
    public Optional<ProjectRow> lock(UUID projectId) {
        return find(projectId, " FOR UPDATE OF p");
    }

    public Optional<ProjectRow> find(UUID projectId) {
        return find(projectId, "");
    }

    private Optional<ProjectRow> find(UUID projectId, String suffix) {
        return jdbc.sql("""
                SELECT p.id, p.pattern_id, t.title, p.created_at::text
                FROM project p JOIN pattern t ON t.id = p.pattern_id WHERE p.id = ?""" + suffix)
                .param(projectId)
                .query((rs, i) -> new ProjectRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getString(4)))
                .optional();
    }

    public List<StepMeta> stepMetas(UUID patternId) {
        return jdbc.sql("""
                SELECT t.id, t.kind, t.total FROM step t JOIN section s ON s.id = t.section_id
                WHERE s.pattern_id = ? ORDER BY s.ord, t.ord""")
                .param(patternId)
                .query((rs, i) -> new StepMeta(rs.getObject(1, UUID.class), StepIr.Kind.valueOf(rs.getString(2)),
                        (Integer) rs.getObject(3)))
                .list();
    }

    public Map<UUID, StepState> progress(UUID projectId) {
        Map<UUID, StepState> out = new HashMap<>();
        jdbc.sql("SELECT step_id, done, finished FROM progress WHERE project_id = ?").param(projectId)
                .query((rs, i) -> out.put(rs.getObject(1, UUID.class),
                        new StepState(rs.getObject(1, UUID.class), rs.getInt(2), rs.getBoolean(3))))
                .list();
        return out;
    }

    public Map<UUID, String> notes(UUID projectId) {
        Map<UUID, String> out = new HashMap<>();
        jdbc.sql("SELECT step_id, note FROM progress WHERE project_id = ? AND note IS NOT NULL").param(projectId)
                .query((rs, i) -> out.put(rs.getObject(1, UUID.class), rs.getString(2)))
                .list();
        return out;
    }

    /** Notes survive progress changes (rewind/undo only touch done/finished). */
    public void saveState(UUID userId, UUID projectId, StepState s) {
        jdbc.sql("""
                INSERT INTO progress (user_id, project_id, step_id, done, finished) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (project_id, step_id) DO UPDATE SET done = EXCLUDED.done, finished = EXCLUDED.finished""")
                .params(userId, projectId, s.stepId(), s.done(), s.finished())
                .update();
    }

    public void saveNote(UUID userId, UUID projectId, UUID stepId, String note) {
        jdbc.sql("""
                INSERT INTO progress (user_id, project_id, step_id, note) VALUES (?, ?, ?, ?)
                ON CONFLICT (project_id, step_id) DO UPDATE SET note = EXCLUDED.note""")
                .params(userId, projectId, stepId, note)
                .update();
    }

    public void addEvent(UUID userId, UUID projectId, String payloadJson) {
        jdbc.sql("INSERT INTO progress_event (user_id, project_id, payload) VALUES (?, ?, CAST(? AS jsonb))")
                .params(userId, projectId, payloadJson)
                .update();
    }

    public Optional<EventRow> lastActiveEvent(UUID projectId) {
        return jdbc.sql("""
                SELECT id, payload::text FROM progress_event
                WHERE project_id = ? AND undone_at IS NULL ORDER BY id DESC LIMIT 1""")
                .param(projectId)
                .query((rs, i) -> new EventRow(rs.getLong(1), rs.getString(2)))
                .optional();
    }

    public void markUndone(long eventId) {
        jdbc.sql("UPDATE progress_event SET undone_at = now() WHERE id = ?").param(eventId).update();
    }
}
