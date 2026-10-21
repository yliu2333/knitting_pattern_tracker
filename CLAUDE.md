# Knitting Pattern Tracker (working name)

## Product
Users upload a knitting pattern they **purchased** (any language: English, Japanese, Chinese, …) → app turns it into a **row-by-row progress tracker in the user's chosen language (default 中文)** with explicit stitch counts per segment, and can **recalculate the pattern for the user's own gauge**.

Target users: Chinese knitters who buy original-language patterns but knit from Chinese and can't read symbol charts.
Prototype: `fixtures/private/reference/paofu-tee-tracker.html` (private repo, see Fixtures) — the 泡芙 Tee single-file HTML tracker (hard-coded steps, checkbox progress, undo/rewind, technique links, yarn-weight log). It used the claude.ai artifact `window.claude` db API for sync; replace that with our backend. Port the UX, not the storage code.

## Hard rules
- **Copyright**: uploaded patterns are private to the uploader. No sharing, publishing, or exporting translated patterns to other users. Never ship third-party patterns in the app.
- **LLM never does math.** LLM only parses source → canonical JSON matching our schema. All stitch counts, gauge conversion, increase/decrease distribution are deterministic Java code.
- **LLM calls per pattern: one parse + at most one "define" call per attempt for techniques the library doesn't know** (all cached, see Isolation and Technique library). Tracking uses zero LLM calls.
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
- LLM: Gemini behind `LlmClient`; **model chain cheapest first** (`LlmTiers`): a parse escalates to the next model only when the cheaper one can't produce an IR that passes validation + simulation in 2 attempts (or is unavailable). Cost is ~$0.04 (Flash-Lite) to ~$0.10 (Flash) per pattern; tracking is free
- Repo layout: `/backend`, `/ios`, `/fixtures` (public sample IR, written by us)

## Fixtures & copyright (this repo is public)
- Purchased patterns (泡芙 Tee IR, prototype HTML) live ONLY in the private repo `yliu2333/knitting-private-fixtures`, cloned or symlinked to `fixtures/private/` (git-ignored). Never commit pattern content here, not even temporarily: git history is permanent.
- Tests discover `fixtures/*.ir.json` + `fixtures/private/patterns/*.ir.json`; private ones are skipped when absent.
- `expected/<name>.expected.json` (private): stitch counts the pattern itself states, in order. Both the hand-written IR (`ExpectedCountsTest`) and Gemini's parse (`LivePrivateSourcesTest`, saves raw output to private `parsed/`) must hit them as a subsequence of step end counts.
- IR simulation semantics (StitchSimulator):
  - a row/round starting at BOR slips it implicitly; `K until END` crossing markers is split into literal pieces (`k28, sm, k9…`); inside `REPEAT until END` markers are slipped silently
  - `until END` = BOR on a joined round, the edge on a flat piece; `stopBefore` works with MARKER/BOR/END and on `REPEAT until …`
  - a CHECKPOINT on a joined round is worked as a round; a ROUND step on an unjoined piece joins it (turning to the RS first if a flat piece ended on a WS row); if the piece has no BOR yet, that first round builds the ring (front + cast-on + back + cast-on + pm BOR) and the piece is joined after it
  - pieces: `HOLD` (count/until, `attrs.piece` name; markers in the span are dropped) keeps the round joined unless the next action is `TURN`, which opens the round there (flat piece, edge markers dropped); flat ROW steps turn at the edge implicitly; `PUT_ASIDE` sets the whole piece aside; `PICKUP_HELD` makes a held piece the working piece; `JOIN_PIECE` puts held sts on the left needle mid-row; `RESUME_MAIN` returns to the body; `PM BOR` moves an existing BOR
  - `PICKUP_EDGE`, `CO_*` add sts; `BO*` empties the needle (markers too)

## Technique library (learns new techniques, never duplicates)
- Source of truth for shipped techniques: `resources/techniques/builtin.json` (kind, structural `def`, names, zh/en templates, EN/JA/ZH aliases). Seeded into `technique` / `technique_alias` / `technique_i18n` by the repeatable Java migration `db/migration/R__Builtin_techniques`. The parse prompt's glossary is generated from the user's library (`GlossaryPrompt`).
- Kinds: `PRIMITIVE` (Java semantics: K/P/REPEAT/markers/TURN/pieces… not learnable), `STITCH` (consumes → produces + descriptors worked/lean/tbl/yarn/source/method/cross), `BINDOFF` (empties the needle), `FINISHING` (no count effect: seaming, blocking…), `MACRO` (steps of existing codes).
- Learning (`TechniqueLearner`, prompt `llm/define-system.md`): unknown term → alias lookup (no LLM) → one define call with the whole library listed → `DefinitionValidator` → de-dup: `aliasOf` answer, same structural `Signature` as an existing technique, or a name already owned by one ⇒ it becomes an **alias**, never a second technique; a name owned by a different structure is rejected. Accepted ones are PERSONAL (`user_technique*`, RLS) and marked verified when the pattern's stated counts all matched.
- Names: one normalized name (`AliasNorm`: NFKC, case, separators) → exactly one technique (PK on `alias_norm`). UI shows the canonical name + "也叫" (`GET /api/techniques`).
- Promotion (`technique_promote()`, SECURITY DEFINER): a structure taught by ≥ 2 users (≥ 1 verified) becomes GLOBAL; an alias two users agree on becomes global. Only definitions, names and templates are copied, never pattern content.
- Stated counts: the parser copies totals the pattern states (`statedEndSts`, `VERIFY_COUNT count`); the simulator fails on any mismatch. This is what verifies both the parse and newly learned techniques.

## Backend layering (same as video-platform: api / service / dao)
```
api/        REST controllers, DTOs
service/    business logic
  parse/    PatternParser implementations
  count/    StitchSimulator: knits the IR stitch by stitch → literal counts per segment, start/end sts (all stitch math)
  render/   InstructionRenderer (resolved segments + templates from the user's TechniqueLibrary → text)
  technique/ TechniqueLibrary, Signature, AliasNorm, TechniqueLearner, DefinitionValidator, GlossaryPrompt
  gauge/    GaugeAdapter (pure functions, heavily unit-tested)
  llm/      LlmClient interface + GeminiClient, GroqClient
security/   CurrentUser, JWT filter, UserScope (sets app.user_id per transaction)
dao/        JdbcClient repositories
ir/         canonical IR records (PatternIr → SectionIr → StepIr → RowIr → SegmentIr) + IrValidator
```

## Local dev
- `cd backend && docker compose up -d` (Postgres+pgvector; `db/init` creates app role `kpt_app`). Flyway migrates as owner `kpt_owner`; the app connects as `kpt_app`.
- `./gradlew test` (Testcontainers; needs Docker).
- Env: `JWT_SECRET` (≥ 32 bytes, required), `GEMINI_API_KEY` (may be empty when an egress proxy injects `x-goog-api-key`, e.g. a Claude cloud network secret for `generativelanguage.googleapis.com`), `GEMINI_MODELS` (comma list, cheapest first; default `gemini-3.1-flash-lite,gemini-3.8-flash`; 2.5-flash is closed to new keys), `GEMINI_THINKING_LEVEL` (low|medium|high, empty = model default; use `low` behind the Claude cloud proxy, which drops responses with no byte in ~30s), `APPLE_CLIENT_IDS` (iOS bundle id(s); empty = Apple login off), `DB_URL`, `DB_APP_USER/PASSWORD`, `DB_OWNER_USER/PASSWORD`.

## Auth
- iOS: Sign in with Apple with `request.nonce = sha256(rawNonce)` → `POST /api/auth/apple {identityToken, nonce: rawNonce}` → `{userId, accessToken (1h), expiresIn, refreshToken (60d)}`
- `POST /api/auth/refresh {refreshToken}` rotates (single use; reusing an old one revokes all of the user's tokens) · `POST /api/auth/logout {refreshToken}`
- Apple token check: Apple JWKS signature, iss `https://appleid.apple.com`, aud ∈ `APPLE_CLIENT_IDS`, exp, nonce hash.
- `refresh_token` table: app role has no grants; only SECURITY DEFINER functions (`refresh_token_issue/use/revoke`) touch it. Only hashes are stored.
- TODO before App Store: on account deletion also revoke the Apple token via Apple's REST API (needs a Sign in with Apple private key).

## API (all require `Authorization: Bearer <our JWT>`, subject = app_user id)
- `GET /api/me` · `PATCH /api/me {uiLang}` · `DELETE /api/me` (deletes account + all data; App Store requirement)
- `POST /api/patterns/upload` (multipart `file` = PDF / image / text, optional `size`) → LLM parse → 201; same file+size again → 200 cached, no LLM call; parse failure → 422 `{errors}`
- `POST /api/patterns` (IR JSON; same IR again → 200 existing id) · `GET /api/patterns`
- `GET /api/techniques?lang=` → caller's library: code, kind, name, alsoKnownAs, origin (BUILTIN/GLOBAL/PERSONAL)
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
- `technique(code, kind, def, signature unique, origin, names)` · `technique_alias(alias_norm PK, alias, lang, code)` — global library
- `user_technique(user_id, code, kind, def, signature, names, templates, verified)` · `user_technique_alias(user_id, alias_norm, alias, lang, code)` — learned, RLS
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
5. Sign in with Apple + JWT — ✅ backend; iOS side comes with milestone 4
6. TextPatternParser: LLM prompt + glossary + JSON schema validation + retry; test on real EN/JP/CN patterns
   - ✅ pipeline: `resources/llm/parse-system.md` + `glossary.md` → Gemini (JSON mode, SSE streaming, key in header) → strict Jackson → IrNormalizer (Java does REPEAT totals, inch→cm) → IrValidator → StitchSimulator; errors fed back, max 3 attempts. Parser version `text-v3/<model+model…>` (the whole chain) is part of the cache key: bump it when the prompt changes.
   - ⏳ live quality check: `GEMINI_LIVE=1 GEMINI_THINKING_LEVEL=low ./gradlew test --tests '*Live*'`; real patterns go in the private repo `sources/` with an `expected/` file
   - **Free tier = 20 requests/day per model** (one parse can use up to 6). Never loop retries on 503/429: run once, stop and report. Check offline first by feeding a saved raw output (private `parsed/`) through IrNormalizer → IrValidator → StitchSimulator.
   - Sheer na Knit (JA) first live run: 3/3 attempts failed on IR gaps (armhole split/rejoin, edge pick-up, "to N before BOR"). Fixed in simulator + prompt (`text-v2`).
   - Second run (`text-v3`, gemini-3.6-flash): 78 → 274 → 136 → 136 correct; failed at the flat → round rejoin (front ended on WS). Simulator now turns + defers the join. Next: re-run once quota resets.
7. GaugeAdapter: rescale counts with constraints (ribbing parity, repeat multiples, even decrease spacing, short-row depth by row gauge)
8. Later: JP knit symbol charts → crochet charts

## Conventions
- Explain new Swift/iOS concepts by mapping to Java equivalents.
- Keep explanations short.
- Technical terms in English; app UI text in Chinese by default, i18n-ready.
