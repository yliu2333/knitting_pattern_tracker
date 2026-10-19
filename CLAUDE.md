# Knitting Pattern Tracker (working name)

## Product
Users upload a knitting pattern they **purchased** (any language: English, Japanese, Chinese, …) → app turns it into a **row-by-row progress tracker in the user's chosen language (default 中文)** with explicit stitch counts per segment, and can **recalculate the pattern for the user's own gauge**.

Target users: Chinese knitters who buy original-language patterns but knit from Chinese and can't read symbol charts.
Prototype: `fixtures/private/reference/paofu-tee-tracker.html` (private repo, see Fixtures) — the 泡芙 Tee single-file HTML tracker (hard-coded steps, checkbox progress, undo/rewind, technique links, yarn-weight log). It used the claude.ai artifact `window.claude` db API for sync; replace that with our backend. Port the UX, not the storage code.

## Hard rules
- **Copyright**: uploaded patterns are private to the uploader. No sharing, publishing, or exporting translated patterns to other users. Never ship third-party patterns in the app.
- **LLM never does math.** LLM only parses source → canonical JSON matching our schema. All stitch counts, gauge conversion, increase/decrease distribution are deterministic Java code.
- **One LLM call per pattern** (cached, see Isolation). Tracking uses zero LLM calls.
- Instructions always state literal counts per segment ("下针 17，放记号圈，下针 9…"), never "knit to marker".
- **User data isolation is non-negotiable** (see section below).

## Language pipeline (compiler-style: frontend → IR → backend)
Do NOT translate source → English → target. Two hops of translation lose meaning, and knitting terms are where translation breaks.
```
source (any lang) --[LLM parse]--> Canonical IR (language-neutral) --[deterministic render]--> user's language
```
- **Parse (frontend)**: LLM detects `source_lang` and outputs canonical JSON directly. Stitches/actions are **codes**, not words: `K`, `P`, `K2TOG`, `SSK`, `M1L`, `M1R`, `YO`, `PM`, `SM`, `W&T`… The prompt includes a glossary per source language (EN abbreviations, JP 編み記号 names, CN terms) mapping to codes.
- **IR**: sections / steps / segments with codes + counts. No natural-language instructions needed for anything the tracker computes.
- **Render (backend)**: `InstructionRenderer` + `technique_i18n(code, lang, name, short_desc, video_url)` table. Segment `{K,17}` → zh "下针 17" / en "k17" / ja "表目 17". Fully deterministic, zero LLM, adding a language = adding dictionary rows.
- **Free text** that can't be coded (designer notes, "try on before separating"): store `note_src` + `source_lang`; translate once with LLM into `note_i18n` jsonb on demand, cached per (note, lang).
- Unknown terms → LLM emits `{"code":"UNKNOWN","raw":"..."}`; UI shows the raw text and flags it, never guesses silently.

## Stack
- Backend: Java 21, Spring Boot 3, PostgreSQL + pgvector (JSONB for extensible structure), Flyway migrations
- iOS: SwiftUI
- Auth: Sign in with Apple → backend-issued JWT
- LLM: free tier (Gemini Flash first; Groq as fallback), behind an interface
- Repo layout: `/backend`, `/ios`, `/fixtures` (public sample IR, written by us)

## Fixtures & copyright (this repo is public)
- Purchased patterns (泡芙 Tee IR, prototype HTML) live ONLY in the private repo `yliu2333/knitting-private-fixtures`, cloned or symlinked to `fixtures/private/` (git-ignored). Never commit pattern content here, not even temporarily: git history is permanent.
- Tests discover `fixtures/*.ir.json` + `fixtures/private/patterns/*.ir.json`; private ones are skipped when absent.
- IR simulation semantics: a row/round starting at BOR slips it implicitly; `K until END` crossing markers is split into literal pieces (`k28, sm, k9…`); inside `REPEAT until END` markers are slipped silently; `PICKUP_HELD` / `RESUME_MAIN` switch the active piece.

## Backend layering (same as video-platform: api / service / dao)
```
api/        REST controllers, DTOs
service/    business logic
  parse/    PatternParser implementations
  count/    StitchSimulator: knits the IR stitch by stitch → literal counts per segment, start/end sts (all stitch math)
  render/   InstructionRenderer (resolved segments + technique_i18n templates → text)
  gauge/    GaugeAdapter (pure functions, heavily unit-tested)
  llm/      LlmClient interface + GeminiClient, GroqClient
security/   CurrentUser, JWT filter, UserScope (sets app.user_id per transaction)
dao/        JdbcClient repositories
ir/         canonical IR records (PatternIr → SectionIr → StepIr → RowIr → SegmentIr) + IrValidator
```

## Local dev
- `cd backend && docker compose up -d` (Postgres+pgvector; `db/init` creates app role `kpt_app`). Flyway migrates as owner `kpt_owner`; the app connects as `kpt_app`.
- `./gradlew test` (Testcontainers; needs Docker).
- Env: `JWT_SECRET` (≥ 32 bytes, required), `DB_URL`, `DB_APP_USER/PASSWORD`, `DB_OWNER_USER/PASSWORD`.

## API (all require `Authorization: Bearer <our JWT>`, subject = app_user id)
- `POST /api/patterns` (IR JSON; same IR again → 200 existing id) · `GET /api/patterns`
- `POST /api/projects {patternId}` · `GET /api/projects` · `GET /api/projects/{id}` → TrackerView (sections, steps, done/finished, start/end sts, currentStepId, percent, lastAction)
- `GET /api/projects/{id}/steps/{stepId}/rounds/{round}?lang=zh|en` → literal instruction text for one round (0-based)
- `POST …/steps/{stepId}/advance | retreat | finish | rewind?round=n` · `PUT …/steps/{stepId}/note` · `POST /api/projects/{id}/undo`
- Missing or another user's resource → 404 (indistinguishable). Invalid state → 409. Undo = restore `before` snapshot of the last non-undone `progress_event`; notes are not part of undo.

## Extension points (design now, implement later)
```java
enum Craft { KNIT, CROCHET }
enum SourceType { TEXT_PDF, TEXT_IMAGE, JP_KNIT_CHART, CROCHET_CHART }

interface PatternParser {
    boolean supports(SourceType type, Craft craft);
    ParsedPattern parse(SourceFile file);   // returns canonical IR, any source_lang
}
// v1: TextPatternParser (text patterns in any language). Later: JpKnitChartParser, CrochetChartParser.

interface LlmClient {
    String completeJson(String prompt, JsonSchema schema, List<Image> images);
}
```
- Knit charts ≈ 2D grid; crochet charts ≈ graph (each stitch points to the stitch it's worked into, rounds can be radial). Keep chart-specific data in a JSONB `structure` field so the core Step model stays craft-agnostic.

## User data isolation (lesson from AI health assistant: histories got mixed)
Root cause of that class of bug: isolation relied on every query *remembering* to filter. Here, isolation is enforced by layers that fail closed.
1. **Identity only from the token.** `userId` comes from `CurrentUser` (JWT), never from request params/body. No endpoint accepts `userId`.
2. **Postgres Row-Level Security** on every user-owned table (`pattern, project, progress, progress_event, yarn_log, embedding, …`):
   `USING (user_id = current_setting('app.user_id')::uuid)`. A per-request interceptor runs `SET LOCAL app.user_id = ?` inside the transaction. App DB role is NOT table owner and has no `BYPASSRLS`. Forgetting a `WHERE` → returns nothing, not someone else's data.
3. **Denormalize `user_id`** onto every user-owned row (incl. child tables like `progress`, `segment` of user patterns) so RLS is a simple column check, no joins.
   FKs between user-owned tables are composite `(parent_id, user_id) → parent(id, user_id)`: FK checks bypass RLS, so a plain FK would let B attach rows to A's parent.
4. **Vectors live in pgvector in the same DB**, same RLS policy. No separate vector store where a metadata filter is optional. Retrieval is also scoped by `project_id`/`pattern_id` so one user's projects don't bleed into each other.
5. **LLM calls are stateless.** Each call gets only the context explicitly built for that request. No shared conversation buffer, no static/singleton state holding history. Any chat history is a table with `user_id` + RLS.
6. **Cache keys include `user_id`**: parse cache = `(user_id, file_hash, parser_version)`. No cross-user reuse of parsed results in v1.
7. **Tests**: integration test suite (Testcontainers Postgres) with users A and B — for every endpoint, B accessing A's resource must return 404; plus a test that queries a table *without* setting `app.user_id` and asserts 0 rows.
8. Logs: never log pattern content or LLM prompts with user data at INFO.

## Data model (v1)
- `app_user(id, apple_sub, ui_lang default 'zh', created_at)`
- `pattern(id, user_id, title, craft, source_type, source_lang, file_hash, parser_version, original_gauge jsonb, status)`
- `section(id, user_id, pattern_id, ord, name_key, name_src)`
- `step(id, user_id, section_id, ord, kind[ROWS|REPEAT|CHECKPOINT], unit[ROW|ROUND], title_key, title_src, note_src, note_i18n jsonb, needle_mm, target jsonb, start_sts, end_sts, total, group_count, rows_per_group, structure jsonb)`
  - `total` null = "knit until X cm" (open-ended, `target` e.g. `{"lengthCm":6,"from":"CAST_ON"}`)
  - `start_sts` / `end_sts` are computed by Java, never by the LLM
- `step_row(id, user_id, step_id, ord, side[RS|WS], name_key, name_src)` — row templates; round i of a step uses row `i % rowCount`
- `segment(id, user_id, step_row_id, parent_id, ord, code, count, until[MARKER|BOR|GAP|END], stop_before, marker, attrs jsonb, raw)` — canonical per-segment action
  - "knit to 1 before marker" = `{K, until:MARKER, stopBefore:1}`, `count` null; Java resolves the literal count
  - `REPEAT` segments own children via `parent_id` (`count` times, or `until`)
  - markers are segments (`PM`/`SM`/`RM` with optional `marker` name), not a flag
- `technique_i18n(code, lang, name, template, short_desc, video_url)` — global, not user-owned; `template` drives rendering (`下针 {n}` / `k{n}`)
- `project(id, user_id, pattern_id, user_gauge jsonb, target_size jsonb, adapted jsonb)` — one pattern can have multiple projects
- `progress(user_id, project_id, step_id, done, finished, note)`
- `progress_event(id, user_id, project_id, payload jsonb, created_at)` — append-only history for undo / rewind (拆线)
- `yarn_log(id, user_id, project_id, step_id, grams, created_at)`
- `embedding(id, user_id, pattern_id, chunk_ref, vector)` — later, only if an in-app assistant needs retrieval

## Milestones
1. ✅ Schema + migrations **with RLS from day one**; hand-write 泡芙 Tee as canonical-IR fixture JSON and load it
2. ✅ InstructionRenderer (zh + en) over the fixture; output must match the prototype's per-segment text
   - Parity test (private): stitch counts per round, counts between markers, and short-row literal counts all equal the prototype's
3. ✅ Progress API: mark row/round done, finish step, undo, rewind-to-step; A/B isolation tests
4. SwiftUI tracker reading the API (port prototype UX: current step, n/N, segments, next step, undo)
5. Sign in with Apple + JWT
6. TextPatternParser: LLM prompt + glossary + JSON schema validation + retry; test on real EN/JP/CN patterns
7. GaugeAdapter: rescale counts with constraints (ribbing parity, repeat multiples, even decrease spacing, short-row depth by row gauge)
8. Later: JP knit symbol charts → crochet charts

## Conventions
- Explain new Swift/iOS concepts by mapping to Java equivalents.
- Keep explanations short.
- Technical terms in English; app UI text in Chinese by default, i18n-ready.
