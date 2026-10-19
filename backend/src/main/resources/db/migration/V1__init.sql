-- Runs as the schema owner (Flyway). The app connects as ${appRole}, which is NOT the owner
-- and has no BYPASSRLS, so every user-owned table below is filtered by RLS for it.
-- The role itself is created outside migrations (see backend/db/init/01-roles.sql).

CREATE EXTENSION IF NOT EXISTS vector;

-- NULLIF: once set in a session, an unset LOCAL value reads back as '' rather than NULL.
CREATE FUNCTION current_app_user() RETURNS uuid
    LANGUAGE sql STABLE
    AS $$ SELECT NULLIF(current_setting('app.user_id', true), '')::uuid $$;

CREATE TABLE app_user (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    apple_sub   text NOT NULL UNIQUE,
    ui_lang     text NOT NULL DEFAULT 'zh',
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE pattern (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    title           text NOT NULL,
    craft           text NOT NULL CHECK (craft IN ('KNIT', 'CROCHET')),
    source_type     text NOT NULL CHECK (source_type IN ('TEXT_PDF', 'TEXT_IMAGE', 'JP_KNIT_CHART', 'CROCHET_CHART')),
    source_lang     text NOT NULL,
    file_hash       text NOT NULL,
    parser_version  text NOT NULL,
    original_gauge  jsonb,
    status          text NOT NULL DEFAULT 'READY' CHECK (status IN ('PARSING', 'READY', 'FAILED')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (user_id, file_hash, parser_version)   -- parse cache key, never shared across users
);

CREATE TABLE section (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    pattern_id  uuid NOT NULL REFERENCES pattern(id) ON DELETE CASCADE,
    ord         int  NOT NULL,
    name_key    text,
    name_src    text,
    UNIQUE (pattern_id, ord)
);

CREATE TABLE step (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    section_id      uuid NOT NULL REFERENCES section(id) ON DELETE CASCADE,
    ord             int  NOT NULL,
    kind            text NOT NULL CHECK (kind IN ('ROWS', 'REPEAT', 'CHECKPOINT')),
    unit            text CHECK (unit IN ('ROW', 'ROUND')),
    title_key       text,
    title_src       text,
    note_src        text,
    note_i18n       jsonb,
    needle_mm       numeric(4, 2),
    target          jsonb,          -- open-ended steps: e.g. {"lengthCm": 6, "from": "CAST_ON"}
    start_sts       int,            -- computed by Java, never by the LLM
    end_sts         int,
    total           int,            -- null = open-ended ("knit until X cm")
    group_count     int,
    rows_per_group  int,
    structure       jsonb,          -- chart-specific data (knit grid / crochet graph)
    UNIQUE (section_id, ord),
    CHECK (kind = 'CHECKPOINT' OR unit IS NOT NULL)
);

-- One row template inside a step; a step's rows cycle (row ord = round index mod row count).
CREATE TABLE step_row (
    id        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id   uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    step_id   uuid NOT NULL REFERENCES step(id) ON DELETE CASCADE,
    ord       int  NOT NULL,
    side      text CHECK (side IN ('RS', 'WS')),
    name_key  text,
    name_src  text,
    UNIQUE (step_id, ord)
);

-- Canonical per-segment action. REPEAT segments own children via parent_id.
CREATE TABLE segment (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    step_row_id  uuid NOT NULL REFERENCES step_row(id) ON DELETE CASCADE,
    parent_id    uuid REFERENCES segment(id) ON DELETE CASCADE,
    ord          int  NOT NULL,
    code         text NOT NULL,
    count        int,              -- null = resolved by Java from "until"
    until        text CHECK (until IN ('MARKER', 'BOR', 'GAP', 'END')),
    stop_before  int  NOT NULL DEFAULT 0,
    marker       text,             -- marker name for PM / SM / RM
    attrs        jsonb,
    raw          text              -- original wording, kept for UNKNOWN codes
);
CREATE INDEX segment_row_idx ON segment (step_row_id, parent_id, ord);

CREATE TABLE technique_i18n (
    code        text NOT NULL,
    lang        text NOT NULL,
    name        text NOT NULL,
    short_desc  text,
    video_url   text,
    PRIMARY KEY (code, lang)
);

CREATE TABLE project (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    pattern_id   uuid NOT NULL REFERENCES pattern(id) ON DELETE CASCADE,
    user_gauge   jsonb,
    target_size  jsonb,
    adapted      jsonb,
    created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE progress (
    user_id     uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    project_id  uuid NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    step_id     uuid NOT NULL REFERENCES step(id) ON DELETE CASCADE,
    done        int  NOT NULL DEFAULT 0,
    finished    boolean NOT NULL DEFAULT false,
    note        text,
    PRIMARY KEY (project_id, step_id)
);

CREATE TABLE progress_event (
    id          bigserial PRIMARY KEY,
    user_id     uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    project_id  uuid NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    payload     jsonb NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX progress_event_project_idx ON progress_event (project_id, id);

CREATE TABLE yarn_log (
    id          bigserial PRIMARY KEY,
    user_id     uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    project_id  uuid NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    step_id     uuid REFERENCES step(id) ON DELETE SET NULL,
    grams       numeric(7, 1) NOT NULL CHECK (grams >= 0),
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE embedding (
    id          bigserial PRIMARY KEY,
    user_id     uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    pattern_id  uuid NOT NULL REFERENCES pattern(id) ON DELETE CASCADE,
    chunk_ref   text NOT NULL,
    vector      vector(768) NOT NULL
);

-- ---------- Row-Level Security: fail closed ----------
-- app_user is not FORCEd: app_user_upsert (owner, SECURITY DEFINER) must see it before login.
ALTER TABLE app_user ENABLE ROW LEVEL SECURITY;
CREATE POLICY own_row ON app_user
    USING (id = current_app_user()) WITH CHECK (id = current_app_user());

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['pattern', 'section', 'step', 'step_row', 'segment', 'project',
                             'progress', 'progress_event', 'yarn_log', 'embedding'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY own_rows ON %I USING (user_id = current_app_user()) '
                       'WITH CHECK (user_id = current_app_user())', t);
    END LOOP;
END $$;

-- Sign-in must find a user before app.user_id is known; this is the only path around RLS.
CREATE FUNCTION app_user_upsert(p_apple_sub text) RETURNS uuid
    LANGUAGE sql SECURITY DEFINER SET search_path = public
    AS $$
        INSERT INTO app_user (apple_sub) VALUES (p_apple_sub)
        ON CONFLICT (apple_sub) DO UPDATE SET apple_sub = EXCLUDED.apple_sub
        RETURNING id
    $$;
REVOKE ALL ON FUNCTION app_user_upsert(text) FROM PUBLIC;

-- ---------- grants for the app role ----------
GRANT USAGE ON SCHEMA public TO ${appRole};
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO ${appRole};
GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO ${appRole};
REVOKE INSERT, UPDATE, DELETE ON technique_i18n FROM ${appRole};
GRANT EXECUTE ON FUNCTION app_user_upsert(text), current_app_user() TO ${appRole};
