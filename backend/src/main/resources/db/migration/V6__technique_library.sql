-- Technique library. Global tables hold built-in and promoted techniques (no user data, no pattern text);
-- user_technique* hold techniques a user's patterns taught the system, private to that user until promoted.

ALTER TABLE step ADD COLUMN stated_end_sts int;   -- count the pattern states for the step, checked by the simulator

CREATE TABLE technique (
    code        text PRIMARY KEY,
    kind        text NOT NULL CHECK (kind IN ('PRIMITIVE', 'STITCH', 'BINDOFF', 'FINISHING', 'MACRO')),
    def         jsonb,
    signature   text UNIQUE,              -- structural identity: one technique per structure
    origin      text NOT NULL CHECK (origin IN ('BUILTIN', 'GLOBAL')),
    names       jsonb NOT NULL DEFAULT '{}',
    created_at  timestamptz NOT NULL DEFAULT now()
);

-- One normalized name → exactly one technique. This is what keeps aliases from becoming duplicates.
CREATE TABLE technique_alias (
    alias_norm  text PRIMARY KEY,
    alias       text NOT NULL,
    lang        text,
    code        text NOT NULL REFERENCES technique(code) ON DELETE CASCADE
);

CREATE TABLE user_technique (
    user_id     uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    code        text NOT NULL,
    kind        text NOT NULL CHECK (kind IN ('STITCH', 'BINDOFF', 'FINISHING', 'MACRO')),
    def         jsonb,
    signature   text,
    names       jsonb NOT NULL DEFAULT '{}',
    templates   jsonb NOT NULL DEFAULT '{}',
    verified    boolean NOT NULL DEFAULT false,   -- a pattern using it reached its stated stitch counts
    created_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, code),
    UNIQUE (user_id, signature)
);

-- code may name the user's own technique or a global one (a new name for e.g. K2TOG).
CREATE TABLE user_technique_alias (
    user_id     uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    alias_norm  text NOT NULL,
    alias       text NOT NULL,
    lang        text,
    code        text NOT NULL,
    PRIMARY KEY (user_id, alias_norm)
);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['user_technique', 'user_technique_alias'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY own_rows ON %I USING (user_id = current_app_user()) '
                       'WITH CHECK (user_id = current_app_user())', t);
    END LOOP;
END $$;

GRANT SELECT ON technique, technique_alias TO ${appRole};
GRANT SELECT, INSERT, UPDATE, DELETE ON user_technique, user_technique_alias TO ${appRole};

-- Promotion: a learned technique becomes global once two different users' patterns taught the same structure
-- (same signature) and at least one of them verified it. An alias becomes global once two users map it to the
-- same global technique. Runs as owner (sees every user's learned rows) but copies only definitions, names and
-- templates, never pattern content; returns the promoted codes.
CREATE FUNCTION technique_promote() RETURNS SETOF text
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
    AS $$
DECLARE
    r record;
    src user_technique%ROWTYPE;
    new_code text;
BEGIN
    FOR r IN
        SELECT ut.signature
        FROM user_technique ut
        WHERE ut.signature IS NOT NULL
          AND NOT EXISTS (SELECT 1 FROM technique g WHERE g.signature = ut.signature)
        GROUP BY ut.signature
        HAVING count(DISTINCT ut.user_id) >= 2 AND bool_or(ut.verified)
    LOOP
        SELECT * INTO src FROM user_technique WHERE signature = r.signature ORDER BY verified DESC, created_at LIMIT 1;
        new_code := src.code;
        WHILE EXISTS (SELECT 1 FROM technique WHERE code = new_code) LOOP
            new_code := new_code || '_L';
        END LOOP;
        INSERT INTO technique (code, kind, def, signature, origin, names)
        VALUES (new_code, src.kind, src.def, src.signature, 'GLOBAL', src.names);
        INSERT INTO technique_i18n (code, lang, name, template)
        SELECT new_code, t.key, coalesce(src.names ->> t.key, new_code), t.value
        FROM jsonb_each_text(src.templates) t
        ON CONFLICT (code, lang) DO NOTHING;
        RETURN NEXT new_code;
    END LOOP;

    INSERT INTO technique_alias (alias_norm, alias, lang, code)
    SELECT x.alias_norm, min(x.alias), min(x.lang), x.gcode
    FROM (
        SELECT a.user_id, a.alias_norm, a.alias, a.lang, coalesce(g2.code, g1.code) AS gcode
        FROM user_technique_alias a
        LEFT JOIN user_technique ut ON ut.user_id = a.user_id AND ut.code = a.code
        LEFT JOIN technique g1 ON ut.code IS NULL AND g1.code = a.code
        LEFT JOIN technique g2 ON g2.signature = ut.signature
    ) x
    WHERE x.gcode IS NOT NULL
      AND NOT EXISTS (SELECT 1 FROM technique_alias t WHERE t.alias_norm = x.alias_norm)
    GROUP BY x.alias_norm, x.gcode
    HAVING count(DISTINCT x.user_id) >= 2;
END $$;
REVOKE ALL ON FUNCTION technique_promote() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION technique_promote() TO ${appRole};
