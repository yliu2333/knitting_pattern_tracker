-- Foreign-key checks bypass RLS, so a plain FK would let user B attach a row to user A's parent
-- (e.g. a project on A's pattern). Composite FKs on (id, user_id) make the DB reject that.
DO $$
DECLARE
    parent text;
BEGIN
    FOREACH parent IN ARRAY ARRAY['pattern', 'section', 'step', 'step_row', 'segment', 'project'] LOOP
        EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I UNIQUE (id, user_id)', parent, parent || '_id_user_key');
    END LOOP;
END $$;

ALTER TABLE section DROP CONSTRAINT section_pattern_id_fkey,
    ADD FOREIGN KEY (pattern_id, user_id) REFERENCES pattern (id, user_id) ON DELETE CASCADE;
ALTER TABLE step DROP CONSTRAINT step_section_id_fkey,
    ADD FOREIGN KEY (section_id, user_id) REFERENCES section (id, user_id) ON DELETE CASCADE;
ALTER TABLE step_row DROP CONSTRAINT step_row_step_id_fkey,
    ADD FOREIGN KEY (step_id, user_id) REFERENCES step (id, user_id) ON DELETE CASCADE;
ALTER TABLE segment DROP CONSTRAINT segment_step_row_id_fkey,
    ADD FOREIGN KEY (step_row_id, user_id) REFERENCES step_row (id, user_id) ON DELETE CASCADE;
ALTER TABLE segment DROP CONSTRAINT segment_parent_id_fkey,
    ADD FOREIGN KEY (parent_id, user_id) REFERENCES segment (id, user_id) ON DELETE CASCADE;
ALTER TABLE project DROP CONSTRAINT project_pattern_id_fkey,
    ADD FOREIGN KEY (pattern_id, user_id) REFERENCES pattern (id, user_id) ON DELETE CASCADE;
ALTER TABLE progress DROP CONSTRAINT progress_project_id_fkey,
    ADD FOREIGN KEY (project_id, user_id) REFERENCES project (id, user_id) ON DELETE CASCADE;
ALTER TABLE progress DROP CONSTRAINT progress_step_id_fkey,
    ADD FOREIGN KEY (step_id, user_id) REFERENCES step (id, user_id) ON DELETE CASCADE;
ALTER TABLE progress_event DROP CONSTRAINT progress_event_project_id_fkey,
    ADD FOREIGN KEY (project_id, user_id) REFERENCES project (id, user_id) ON DELETE CASCADE;
ALTER TABLE yarn_log DROP CONSTRAINT yarn_log_project_id_fkey,
    ADD FOREIGN KEY (project_id, user_id) REFERENCES project (id, user_id) ON DELETE CASCADE;
ALTER TABLE yarn_log DROP CONSTRAINT yarn_log_step_id_fkey,
    ADD FOREIGN KEY (step_id, user_id) REFERENCES step (id, user_id) ON DELETE SET NULL (step_id);
ALTER TABLE embedding DROP CONSTRAINT embedding_pattern_id_fkey,
    ADD FOREIGN KEY (pattern_id, user_id) REFERENCES pattern (id, user_id) ON DELETE CASCADE;

-- Undo marks an event instead of deleting it: progress_event stays append-only history.
ALTER TABLE progress_event ADD COLUMN undone_at timestamptz;
