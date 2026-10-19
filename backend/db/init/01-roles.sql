-- Local/dev + test only. The app role must never own tables or have BYPASSRLS.
CREATE ROLE kpt_app LOGIN PASSWORD 'kpt_app' NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;
