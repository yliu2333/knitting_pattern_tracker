-- Refresh tokens are looked up before the caller's identity is known, so RLS can't scope them.
-- Instead the app role gets NO access to the table; only these SECURITY DEFINER functions touch it.
-- RLS is enabled with no policy (default deny) as a second lock; not FORCEd so the owner functions work.
CREATE TABLE refresh_token (
    id          bigserial PRIMARY KEY,
    user_id     uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    token_hash  text NOT NULL UNIQUE,      -- sha256 hex; the raw token is never stored
    expires_at  timestamptz NOT NULL,
    revoked_at  timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX refresh_token_user_idx ON refresh_token (user_id);
ALTER TABLE refresh_token ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON refresh_token FROM ${appRole};
REVOKE ALL ON SEQUENCE refresh_token_id_seq FROM ${appRole};

CREATE FUNCTION refresh_token_issue(p_user uuid, p_hash text, p_expires timestamptz) RETURNS void
    LANGUAGE sql SECURITY DEFINER SET search_path = public
    AS $$ INSERT INTO refresh_token (user_id, token_hash, expires_at) VALUES (p_user, p_hash, p_expires) $$;

-- Single use. Presenting an already-used token means it leaked: revoke every token of that user.
CREATE FUNCTION refresh_token_use(p_hash text) RETURNS uuid
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
    AS $$
DECLARE
    t refresh_token%ROWTYPE;
BEGIN
    SELECT * INTO t FROM refresh_token WHERE token_hash = p_hash FOR UPDATE;
    IF NOT FOUND THEN
        RETURN NULL;
    END IF;
    IF t.revoked_at IS NOT NULL THEN
        UPDATE refresh_token SET revoked_at = now() WHERE user_id = t.user_id AND revoked_at IS NULL;
        RETURN NULL;
    END IF;
    UPDATE refresh_token SET revoked_at = now() WHERE id = t.id;
    IF t.expires_at < now() THEN
        RETURN NULL;
    END IF;
    RETURN t.user_id;
END $$;

CREATE FUNCTION refresh_token_revoke(p_hash text) RETURNS void
    LANGUAGE sql SECURITY DEFINER SET search_path = public
    AS $$ UPDATE refresh_token SET revoked_at = now() WHERE token_hash = p_hash AND revoked_at IS NULL $$;

REVOKE ALL ON FUNCTION refresh_token_issue(uuid, text, timestamptz), refresh_token_use(text),
    refresh_token_revoke(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION refresh_token_issue(uuid, text, timestamptz), refresh_token_use(text),
    refresh_token_revoke(text) TO ${appRole};
