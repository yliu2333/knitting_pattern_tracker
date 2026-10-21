package com.knittracker.service.count;

import com.knittracker.ir.PatternIr;
import com.knittracker.ir.RowIr;
import com.knittracker.ir.SectionIr;
import com.knittracker.ir.SegmentIr;
import com.knittracker.ir.SegmentIr.Until;
import com.knittracker.ir.StepIr;
import com.knittracker.service.count.PatternPlan.RoundPlan;
import com.knittracker.service.count.PatternPlan.StepPlan;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knittracker.service.technique.Technique;
import com.knittracker.service.technique.TechniqueLibrary;

/**
 * Knits the IR stitch by stitch to turn "knit to marker" into literal counts. Pure and deterministic:
 * this is where all stitch arithmetic lives (the LLM never does math).
 *
 * <p>Model: the active piece is a ring of tokens (stitches and markers) with a cursor between two
 * tokens. Working forward (RS) consumes the token after the cursor; after TURN, working backward
 * consumes the token before it. Tokens produced by a stitch land where the consumed one was, on the
 * already-worked side of the cursor.
 */
public final class StitchSimulator {

    public static final String BOR = "BOR";

    private static final int MAX_REPEAT = 10_000;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StitchSimulator() {}

    public static PatternPlan simulate(PatternIr ir) {
        return simulate(ir, TechniqueLibrary.builtin());
    }

    /** Non-primitive codes (K2TOG, CO_*, BO_*, learned ones…) are interpreted from the library's definitions. */
    public static PatternPlan simulate(PatternIr ir, TechniqueLibrary library) {
        return new Run(library).simulate(ir);
    }

    private sealed interface Token permits Stitch, Marker {}

    private record Stitch(boolean dbl) implements Token {}

    private record Marker(String name) implements Token {}

    private static final class Piece {
        final List<Token> ring = new ArrayList<>();
        int pos;
        int dir = 1;
        boolean joined;

        int stitches() {
            return (int) ring.stream().filter(t -> t instanceof Stitch).count();
        }
    }

    private static final class Run {
        final TechniqueLibrary library;
        Piece active = new Piece();
        Piece main;
        /** Pieces on hold by name, in the order they were put aside (unnamed holds get HELD_1, HELD_2…). */
        final Map<String, Piece> held = new LinkedHashMap<>();
        int autoHold;
        boolean roundMode;
        String where = "";
        int autoMarker;
        int silentSlip;
        /** The previous action put part of a joined round on hold, leaving a gap at the cursor. */
        boolean holdGap;
        boolean gapBeforeTurn;
        boolean switchedPiece;

        Run(TechniqueLibrary library) {
            this.library = library;
        }

        PatternPlan simulate(PatternIr ir) {
            List<List<StepPlan>> sections = new ArrayList<>();
            for (int s = 0; s < ir.sections().size(); s++) {
                SectionIr sec = ir.sections().get(s);
                List<StepPlan> steps = new ArrayList<>();
                for (int t = 0; t < sec.steps().size(); t++) {
                    where = "sections[" + s + "].steps[" + t + "]";
                    steps.add(step(sec.steps().get(t)));
                }
                sections.add(steps);
            }
            return new PatternPlan(sections);
        }

        StepPlan step(StepIr st) {
            int start = active.stitches();
            switchedPiece = false;
            // A one-off action on a joined round (e.g. the fold-join round) is worked as a round.
            roundMode = st.unit() == StepIr.Unit.ROUND
                    || (st.kind() == StepIr.Kind.CHECKPOINT && active.joined && active.stitches() > 0);
            int n = st.kind() == StepIr.Kind.CHECKPOINT ? 1 : (st.total() == null ? 1 : st.total());
            List<RoundPlan> rounds = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int ord = i % st.rows().size();
                RowIr row = st.rows().get(ord);
                if (roundMode) startRound();
                else if (st.kind() != StepIr.Kind.CHECKPOINT) startRow(row);
                List<Integer> sections = sectionsFromBor();
                List<ResolvedSegment> segs = run(row.segments());
                rounds.add(new RoundPlan(ord, segs, active.stitches(), sections));
            }
            int end = active.stitches();
            // A step that picks up held stitches (or returns to the body) starts on that piece.
            if (switchedPiece) start = end;
            if (st.statedEndSts() != null && st.statedEndSts() != end) {
                throw fail("the pattern says this step ends with " + st.statedEndSts() + " sts, but working it gives " + end);
            }
            if (st.total() == null && st.kind() != StepIr.Kind.CHECKPOINT && end != start) {
                throw fail("open-ended step changes stitch count " + start + " -> " + end);
            }
            return new StepPlan(start, end, rounds);
        }

        void startRound() {
            if (active.dir != 1) throw fail("round started while working backward (missing TURN?)");
            // Knitting in the round joins a piece that was picked up or cast on flat.
            if (!active.joined) join();
            passBor();
        }

        /** Flat rows turn at the edge; a row that begins at BOR slips it (unless it says SM itself). */
        void startRow(RowIr row) {
            if (!active.joined && active.stitches() > 0 && nextIndex() < 0) active.dir = -active.dir;
            if (!"SM".equals(row.segments().getFirst().code())) passBor();
        }

        void join() {
            active.joined = true;
            active.pos %= Math.max(1, active.ring.size());
        }

        /** A row or round that begins at BOR slips it implicitly. */
        void passBor() {
            if (next() instanceof Marker m && m.name().equals(BOR)) {
                int idx = nextIndex();
                active.pos = active.dir == 1 ? idx + 1 : idx;
            }
        }

        List<ResolvedSegment> run(List<SegmentIr> segments) {
            List<ResolvedSegment> out = new ArrayList<>();
            for (SegmentIr g : segments) out.addAll(exec(g));
            return out;
        }

        List<ResolvedSegment> exec(SegmentIr g) {
            int times = g.count() == null ? 1 : g.count();
            gapBeforeTurn = holdGap;
            holdGap = false;
            return switch (g.code()) {
                case SegmentIr.REPEAT -> repeat(g);
                case "K", "P" -> {
                    if (g.count() == null && (g.until() == Until.END || g.until() == Until.BOR) && stopBefore(g) == 0) {
                        yield workToEnd(g);
                    }
                    int n = g.count() != null ? g.count() : stitchesUntil(g.until(), stopBefore(g));
                    for (int i = 0; i < n; i++) work(1, false);
                    yield n == 0 ? List.of() : List.of(resolved(g, n));
                }
                case "DS" -> {
                    work(1, true);
                    yield List.of(resolved(g, 1));
                }
                case "KDS", "PDS" -> {
                    if (!(next() instanceof Stitch st && st.dbl())) throw fail(g.code() + " but next stitch is not a double stitch");
                    work(1, false);
                    yield List.of(resolved(g, 1));
                }
                case "PM" -> {
                    String name = g.marker() != null ? g.marker() : "M" + (++autoMarker);
                    // There is only one BOR: placing it again moves it (e.g. after rejoining pieces).
                    if (name.equals(BOR)) removeMarker(BOR);
                    insert(new Marker(name));
                    yield List.of(resolved(g, 0));
                }
                case "SM" -> {
                    slipMarker(g.marker());
                    yield List.of(resolved(g, 0));
                }
                case "RM" -> {
                    if (!(next() instanceof Marker)) throw fail("RM but next token is not a marker");
                    int idx = nextIndex();
                    active.ring.remove(idx);
                    if (active.dir == -1 || active.pos > active.ring.size()) active.pos = Math.min(idx, active.ring.size());
                    yield List.of(resolved(g, 0));
                }
                case "TURN" -> {
                    if (gapBeforeTurn) openAtGap();
                    active.dir = -active.dir;
                    yield List.of(resolved(g, 0));
                }
                case "JOIN_ROUND" -> {
                    join();
                    yield List.of(resolved(g, 0));
                }
                case "HOLD" -> {
                    int n = g.count() != null ? g.count() : stitchesAhead(g.until()) - stopBefore(g);
                    hold(n, pieceName(g));
                    yield List.of(resolved(g, n));
                }
                case "PUT_ASIDE" -> {
                    // The whole working piece goes on hold; work continues on another piece.
                    active.dir = 1;
                    active.pos = 0;
                    active.joined = false;
                    held.put(pieceName(g), active);
                    active = new Piece();
                    switchedPiece = true;
                    yield List.of(resolved(g, 0));
                }
                case "PICKUP_HELD" -> {
                    Piece p = takeHeld(g);
                    if (main == null && active.stitches() > 0) main = active;
                    active = p;
                    switchedPiece = true;
                    yield List.of(resolved(g, active.stitches()));
                }
                case "JOIN_PIECE" -> {
                    // Held stitches go onto the left needle at the cursor and are worked next.
                    Piece p = takeHeld(g);
                    List<Token> tokens = p.ring.stream().filter(t -> t instanceof Stitch).toList();
                    int at = active.dir == 1 ? active.pos : active.pos;
                    active.ring.addAll(at, active.dir == 1 ? tokens : tokens.reversed());
                    if (active.dir == -1) active.pos += tokens.size();
                    yield List.of(resolved(g, tokens.size()));
                }
                case "RESUME_MAIN" -> {
                    if (main == null) throw fail("RESUME_MAIN but no piece was put aside");
                    active = main;
                    switchedPiece = true;
                    yield List.of(resolved(g, 0));
                }
                case "VERIFY_COUNT" -> {
                    int n = active.stitches();
                    // A count copied from the pattern ("36 sts") must match what the knitting gives.
                    if (g.count() != null && g.count() != n) {
                        throw fail("the pattern says " + g.count() + " sts here, but working it gives " + n);
                    }
                    yield List.of(resolved(g, n));
                }
                case SegmentIr.UNKNOWN -> List.of(resolved(g, 0));
                default -> fromLibrary(g, times);
            };
        }

        /** STITCH: consumes → produces, `times` times; BINDOFF empties the needle; MACRO expands; FINISHING no-op. */
        List<ResolvedSegment> fromLibrary(SegmentIr g, int times) {
            Technique t = library.byCode(g.code()).orElseThrow(() -> fail("unknown technique code " + g.code()));
            return switch (t.kind()) {
                case STITCH -> {
                    String source = t.def() == null ? "" : t.def().path("source").asText("");
                    if (g.count() == null && (source.equals("CAST_ON") || source.equals("EDGE"))) {
                        throw fail(g.code() + " needs a count");
                    }
                    for (int i = 0; i < times; i++) {
                        if (t.consumes() == 0) {
                            for (int k = 0; k < t.produces(); k++) insert(new Stitch(false));
                        } else {
                            work(t.consumes(), t.produces());
                        }
                    }
                    yield List.of(resolved(g, times));
                }
                case BINDOFF -> {
                    // Piece finished: nothing (not even markers) stays on the needle.
                    int n = active.stitches();
                    active.ring.clear();
                    active.pos = 0;
                    active.dir = 1;
                    active.joined = false;
                    yield List.of(resolved(g, n));
                }
                case FINISHING -> List.of(resolved(g, 0));
                case MACRO -> {
                    List<SegmentIr> steps = macroSteps(t);
                    List<ResolvedSegment> out = new ArrayList<>();
                    for (int i = 0; i < times; i++) out.addAll(run(steps));
                    yield out;
                }
                case PRIMITIVE -> throw fail("primitive " + g.code() + " is not handled");
            };
        }

        List<SegmentIr> macroSteps(Technique t) {
            try {
                return List.of(MAPPER.treeToValue(t.def().get("steps"), SegmentIr[].class));
            } catch (Exception e) {
                throw fail("macro " + t.code() + " has invalid steps");
            }
        }

        /**
         * "Knit to end" across markers: emits literal pieces with the markers in between,
         * e.g. k28, sm, k9, sm, k58 — never "knit to marker".
         */
        List<ResolvedSegment> workToEnd(SegmentIr g) {
            List<ResolvedSegment> out = new ArrayList<>();
            while (true) {
                int n = stitchesUntilAnyMarker(g.until());
                for (int i = 0; i < n; i++) work(1, false);
                if (n > 0) out.add(resolved(g, n));
                if (atUntil(g.until())) break;
                Marker m = (Marker) next();
                slipMarker(m.name());
                out.add(ResolvedSegment.of("SM", 0, null, null, null));
            }
            return RepeatCompressor.compress(out);
        }

        /** Stitches before {@code until}, slipping over other markers (for REPEAT … stopBefore). */
        int stitchesAhead(Until until) {
            int savedPos = active.pos;
            int n = 0;
            try {
                for (int steps = 0; steps <= active.ring.size(); steps++) {
                    int idx = nextIndex();
                    if (atUntil(until, idx)) return n;
                    if (active.ring.get(idx) instanceof Stitch) n++;
                    active.pos = active.dir == 1 ? idx + 1 : idx;
                }
                throw fail(until + " not found");
            } finally {
                active.pos = savedPos;
            }
        }

        int stitchesUntilAnyMarker(Until until) {
            int savedPos = active.pos;
            int n = 0;
            try {
                for (int steps = 0; steps <= active.ring.size(); steps++) {
                    int idx = nextIndex();
                    if (atUntil(until, idx) || active.ring.get(idx) instanceof Marker) return n;
                    n++;
                    active.pos = active.dir == 1 ? idx + 1 : idx;
                }
                throw fail(until + " not found");
            } finally {
                active.pos = savedPos;
            }
        }

        List<ResolvedSegment> repeat(SegmentIr g) {
            boolean silent = g.count() == null && (g.until() == Until.END || g.until() == Until.BOR);
            if (silent) silentSlip++;
            try {
                return repeatIterations(g);
            } finally {
                if (silent) silentSlip--;
            }
        }

        List<ResolvedSegment> repeatIterations(SegmentIr g) {
            List<List<ResolvedSegment>> iterations = new ArrayList<>();
            if (g.count() != null) {
                for (int i = 0; i < g.count(); i++) iterations.add(run(g.childrenOrEmpty()));
            } else {
                // "(k2tog) to 1 st before BOR": stop while stopBefore stitches are still left.
                while (!atUntil(g.until()) && (stopBefore(g) == 0 || stitchesAhead(g.until()) > stopBefore(g))) {
                    if (iterations.size() >= MAX_REPEAT) throw fail("REPEAT until " + g.until() + " never ends");
                    int before = active.ring.size() * 31 + active.pos;
                    iterations.add(run(g.childrenOrEmpty()));
                    if (iterations.getLast().isEmpty() && before == active.ring.size() * 31 + active.pos) {
                        throw fail("REPEAT makes no progress");
                    }
                }
            }
            if (iterations.isEmpty()) return List.of();
            boolean same = iterations.stream().allMatch(it -> it.equals(iterations.getFirst()));
            if (same && iterations.size() > 1) return List.of(ResolvedSegment.repeat(iterations.size(), iterations.getFirst()));
            return iterations.stream().flatMap(List::stream).toList();
        }

        // ---------- ring primitives ----------

        int nextIndex() {
            int size = active.ring.size();
            if (size == 0) return -1;
            if (active.dir == 1) {
                if (active.joined) return active.pos % size;
                return active.pos < size ? active.pos : -1;
            }
            if (active.joined) return (active.pos - 1 + size) % size;
            return active.pos > 0 ? active.pos - 1 : -1;
        }

        Token next() {
            int i = nextIndex();
            return i < 0 ? null : active.ring.get(i);
        }

        void work(int consume, boolean dbl) {
            work(consume, 1, dbl);
        }

        void work(int consume, int produce) {
            work(consume, produce, false);
        }

        /** Consumes {@code consume} stitches and produces {@code produce} (double stitches if {@code dbl}). */
        void work(int consume, int produce, boolean dbl) {
            int idx = -1;
            for (int c = 0; c < consume; c++) {
                skipSilentMarkers();
                idx = nextIndex();
                if (idx < 0) throw fail("ran out of stitches");
                if (!(active.ring.get(idx) instanceof Stitch)) {
                    throw fail("expected a stitch but found marker " + ((Marker) active.ring.get(idx)).name());
                }
                active.ring.remove(idx);
                active.pos = idx;
            }
            for (int k = 0; k < produce; k++) active.ring.add(idx, new Stitch(dbl));
            if (active.dir == 1) active.pos = idx + produce;
            else active.pos = idx;
        }

        /** Inside "repeat to end", markers are slipped as you come to them (BOR still ends the round). */
        void skipSilentMarkers() {
            while (silentSlip > 0 && next() instanceof Marker m && !(roundMode && m.name().equals(BOR))) {
                int idx = nextIndex();
                active.pos = active.dir == 1 ? idx + 1 : idx;
            }
        }

        /** Adds a token on the worked side of the cursor. */
        void insert(Token t) {
            if (active.joined && active.dir == 1 && active.pos > active.ring.size()) active.pos %= active.ring.size();
            active.ring.add(active.pos, t);
            if (active.dir == 1) active.pos++;
        }

        void slipMarker(String name) {
            if (!(next() instanceof Marker m)) throw fail("SM but next token is a stitch");
            if (name != null && !name.equals(m.name())) throw fail("SM " + name + " but next marker is " + m.name());
            // A round ends at BOR: "sm" there just starts the next round.
            if (roundMode && m.name().equals(BOR)) return;
            int idx = nextIndex();
            active.pos = active.dir == 1 ? idx + 1 : idx;
        }

        /**
         * Moves n stitches to a held piece; markers inside the span are removed. The round stays joined
         * (knitting on closes the gap, as when sleeves go on hold) unless the next action is TURN.
         */
        void hold(int n, String name) {
            Piece p = new Piece();
            int idx = -1;
            while (p.stitches() < n) {
                idx = nextIndex();
                if (idx < 0) throw fail("HOLD runs past the edge");
                Token t = active.ring.remove(idx);
                if (t instanceof Stitch) p.ring.add(active.dir == 1 ? p.ring.size() : 0, t);
                active.pos = active.dir == 1 ? idx : idx;
            }
            if (held.containsKey(name)) throw fail("piece " + name + " is already on hold");
            held.put(name, p);
            if (active.joined && n > 0) holdGap = true;
        }

        /** TURN right at a hold gap: the round opens there and the rest is worked flat (armhole split). */
        void openAtGap() {
            int gap = active.pos % Math.max(1, active.ring.size());
            List<Token> rotated = new ArrayList<>(active.ring.subList(gap, active.ring.size()));
            rotated.addAll(active.ring.subList(0, gap));
            // A marker left at either new edge no longer separates anything.
            while (!rotated.isEmpty() && rotated.getFirst() instanceof Marker) rotated.removeFirst();
            while (!rotated.isEmpty() && rotated.getLast() instanceof Marker) rotated.removeLast();
            active.ring.clear();
            active.ring.addAll(rotated);
            active.joined = false;
            active.pos = active.dir == 1 ? active.ring.size() : 0;
        }

        String pieceName(SegmentIr g) {
            String name = g.attrs() == null ? "" : g.attrs().path("piece").asText("");
            return name.isBlank() ? "HELD_" + (++autoHold) : name;
        }

        /** Named piece, or the earliest one put on hold when the IR names none. */
        Piece takeHeld(SegmentIr g) {
            String name = g.attrs() == null ? "" : g.attrs().path("piece").asText("");
            if (name.isBlank()) {
                if (held.isEmpty()) throw fail(g.code() + " but nothing is on hold");
                name = held.keySet().iterator().next();
            }
            Piece p = held.remove(name);
            if (p == null) throw fail(g.code() + ": no piece on hold named " + name);
            return p;
        }

        void removeMarker(String name) {
            for (int i = 0; i < active.ring.size(); i++) {
                if (active.ring.get(i) instanceof Marker m && m.name().equals(name)) {
                    active.ring.remove(i);
                    if (active.pos > i) active.pos--;
                    return;
                }
            }
        }

        boolean atUntil(Until until) {
            return atUntil(until, nextIndex());
        }

        private boolean atUntil(Until until, int idx) {
            if (until == null) throw fail("count or until required");
            if (idx < 0) return until == Until.END;
            Token t = active.ring.get(idx);
            return switch (until) {
                case MARKER -> t instanceof Marker;
                case BOR -> t instanceof Marker m && m.name().equals(BOR);
                // On a flat piece the row ends at the edge (handled above), even if BOR sits mid-row.
                case END -> active.joined && t instanceof Marker m && m.name().equals(BOR);
                case GAP -> t instanceof Stitch s && s.dbl();
            };
        }

        /** Stitches between the cursor and {@code until}, minus {@code stopBefore}. Does not move the cursor. */
        int stitchesUntil(Until until, int stopBefore) {
            int size = active.ring.size();
            int n = 0;
            int savedPos = active.pos;
            try {
                for (int steps = 0; steps <= size; steps++) {
                    int idx = nextIndex();
                    if (atUntil(until, idx)) {
                        if (n < stopBefore) throw fail("only " + n + " stitches before " + until + ", need " + stopBefore);
                        return n - stopBefore;
                    }
                    if (active.ring.get(idx) instanceof Marker m) {
                        throw fail("passed marker " + m.name() + " while looking for " + until);
                    }
                    n++;
                    active.pos = active.dir == 1 ? idx + 1 : idx;
                }
                throw fail(until + " not found");
            } finally {
                active.pos = savedPos;
            }
        }

        List<Integer> sectionsFromBor() {
            List<Token> ring = active.ring;
            int bor = -1;
            for (int i = 0; i < ring.size(); i++) {
                if (ring.get(i) instanceof Marker m && m.name().equals(BOR)) bor = i;
            }
            if (bor < 0 || !active.joined) return List.of();
            List<Integer> out = new ArrayList<>();
            int n = 0;
            for (int k = 1; k <= ring.size(); k++) {
                Token t = ring.get((bor + k) % ring.size());
                if (t instanceof Marker) {
                    out.add(n);
                    n = 0;
                } else {
                    n++;
                }
            }
            return out;
        }

        static int stopBefore(SegmentIr g) {
            return g.stopBefore() == null ? 0 : g.stopBefore();
        }

        static ResolvedSegment resolved(SegmentIr g, int count) {
            return ResolvedSegment.of(g.code(), count, g.marker(), g.attrs(), g.raw());
        }

        SimulationException fail(String msg) {
            return new SimulationException(where + ": " + msg);
        }
    }

    public static class SimulationException extends RuntimeException {
        public SimulationException(String message) {
            super(message);
        }
    }
}
