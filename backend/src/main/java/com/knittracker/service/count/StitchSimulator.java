package com.knittracker.service.count;

import com.knittracker.ir.PatternIr;
import com.knittracker.ir.RowIr;
import com.knittracker.ir.SectionIr;
import com.knittracker.ir.SegmentIr;
import com.knittracker.ir.SegmentIr.Until;
import com.knittracker.ir.StepIr;
import com.knittracker.service.count.PatternPlan.RoundPlan;
import com.knittracker.service.count.PatternPlan.StepPlan;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;

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

    /** Codes that don't touch stitches. */
    private static final Set<String> NO_OPS = Set.of("TRY_ON", "WEAVE_ENDS", "BLOCK", "UNKNOWN");

    private StitchSimulator() {}

    public static PatternPlan simulate(PatternIr ir) {
        return new Run().simulate(ir);
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
        Piece active = new Piece();
        Piece main;
        final Deque<Piece> held = new ArrayDeque<>();
        boolean roundMode;
        String where = "";
        int autoMarker;
        int silentSlip;
        boolean switchedPiece;

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
            roundMode = st.unit() == StepIr.Unit.ROUND;
            int n = st.kind() == StepIr.Kind.CHECKPOINT ? 1 : (st.total() == null ? 1 : st.total());
            List<RoundPlan> rounds = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int ord = i % st.rows().size();
                RowIr row = st.rows().get(ord);
                if (roundMode) startRound();
                else if (st.kind() != StepIr.Kind.CHECKPOINT && !"SM".equals(row.segments().getFirst().code())) passBor();
                List<Integer> sections = sectionsFromBor();
                List<ResolvedSegment> segs = run(row.segments());
                rounds.add(new RoundPlan(ord, segs, active.stitches(), sections));
            }
            int end = active.stitches();
            // A step that picks up held stitches (or returns to the body) starts on that piece.
            if (switchedPiece) start = end;
            if (st.total() == null && st.kind() != StepIr.Kind.CHECKPOINT && end != start) {
                throw fail("open-ended step changes stitch count " + start + " -> " + end);
            }
            return new StepPlan(start, end, rounds);
        }

        void startRound() {
            if (active.dir != 1) throw fail("round started while working backward (missing TURN?)");
            if (!active.joined) throw fail("round on a piece that is not joined");
            passBor();
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
            return switch (g.code()) {
                case SegmentIr.REPEAT -> repeat(g);
                case "K", "P" -> {
                    if (g.count() == null && (g.until() == Until.END || g.until() == Until.BOR)) yield workToEnd(g);
                    int n = g.count() != null ? g.count() : stitchesUntil(g.until(), stopBefore(g));
                    for (int i = 0; i < n; i++) work(1, false);
                    yield n == 0 ? List.of() : List.of(resolved(g, n));
                }
                case "K2TOG", "SSK", "P2TOG", "SSP" -> {
                    for (int i = 0; i < times; i++) work(2, false);
                    yield List.of(resolved(g, times));
                }
                case "M1L", "M1R", "M1LP", "M1RP", "YO" -> {
                    for (int i = 0; i < times; i++) insert(new Stitch(false));
                    yield List.of(resolved(g, times));
                }
                case "SL_WYIF", "SL_WYIB", "FOLD_JOIN" -> {
                    for (int i = 0; i < times; i++) work(1, false);
                    yield List.of(resolved(g, times));
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
                    insert(new Marker(g.marker() != null ? g.marker() : "M" + (++autoMarker)));
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
                    active.dir = -active.dir;
                    yield List.of(resolved(g, 0));
                }
                case "JOIN_ROUND" -> {
                    active.joined = true;
                    active.pos %= Math.max(1, active.ring.size());
                    yield List.of(resolved(g, 0));
                }
                case "CO", "CO_LONGTAIL" -> {
                    if (g.count() == null) throw fail(g.code() + " needs a count");
                    for (int i = 0; i < g.count(); i++) insert(new Stitch(false));
                    yield List.of(resolved(g, g.count()));
                }
                case "HOLD" -> {
                    int n = g.count() != null ? g.count() : stitchesUntil(g.until(), stopBefore(g));
                    hold(n);
                    yield List.of(resolved(g, n));
                }
                case "PICKUP_HELD" -> {
                    if (held.isEmpty()) throw fail("PICKUP_HELD but nothing is on hold");
                    if (main == null) main = active;
                    active = held.removeFirst();
                    switchedPiece = true;
                    active.joined = true;
                    active.pos = 0;
                    yield List.of(resolved(g, active.stitches()));
                }
                case "RESUME_MAIN" -> {
                    if (main == null) throw fail("RESUME_MAIN but no piece was put aside");
                    active = main;
                    switchedPiece = true;
                    yield List.of(resolved(g, 0));
                }
                case "BO", "BO_TUBULAR", "CUT_DRAW_THROUGH" -> {
                    int n = active.stitches();
                    active.ring.removeIf(t -> t instanceof Stitch);
                    active.pos = 0;
                    yield List.of(resolved(g, n));
                }
                case "VERIFY_COUNT" -> List.of(resolved(g, active.stitches()));
                default -> {
                    if (!NO_OPS.contains(g.code())) throw fail("unsupported code " + g.code());
                    yield List.of(resolved(g, 0));
                }
            };
        }

        /**
         * "Knit to end" across markers: emits literal pieces with the markers in between,
         * e.g. k28, sm, k9, sm, k58 — never "knit to marker".
         */
        List<ResolvedSegment> workToEnd(SegmentIr g) {
            if (stopBefore(g) > 0) throw fail("stopBefore is not supported with until " + g.until());
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
                while (!atUntil(g.until())) {
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

        /** Consumes {@code consume} stitches and produces one (a double stitch if {@code dbl}). */
        void work(int consume, boolean dbl) {
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
            active.ring.add(idx, new Stitch(dbl));
            if (active.dir == 1) active.pos = idx + 1;
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

        void hold(int n) {
            Piece p = new Piece();
            for (int i = 0; i < n; i++) {
                int idx = nextIndex();
                if (idx < 0 || !(active.ring.get(idx) instanceof Stitch)) throw fail("HOLD runs into a marker or edge");
                p.ring.add(active.ring.remove(idx));
                active.pos = idx;
            }
            held.addLast(p);
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
                case BOR, END -> t instanceof Marker m && m.name().equals(BOR);
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
