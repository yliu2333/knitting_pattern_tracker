package com.knittracker.service.progress;

import com.knittracker.ir.StepIr;
import com.knittracker.service.ConflictException;
import com.knittracker.service.NotFoundException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Pure progress state machine, ported from the prototype tracker. Every action returns the
 * changes it made (before → after) so the caller can persist them and record undo history.
 */
public final class ProgressRules {

    /** total == null: open-ended. A CHECKPOINT counts as total 1. */
    public record StepMeta(UUID id, StepIr.Kind kind, Integer total) {
        public Integer effectiveTotal() {
            return kind == StepIr.Kind.CHECKPOINT ? Integer.valueOf(1) : total;
        }

        public boolean openEnded() {
            return effectiveTotal() == null;
        }
    }

    public record Change(StepState before, StepState after) {}

    private final List<StepMeta> steps;
    private final Map<UUID, StepState> states;

    public ProgressRules(List<StepMeta> steps, Map<UUID, StepState> states) {
        this.steps = List.copyOf(steps);
        this.states = new HashMap<>(states);
    }

    public StepState state(UUID stepId) {
        return states.getOrDefault(stepId, StepState.empty(stepId));
    }

    public boolean isDone(StepMeta m) {
        StepState s = state(m.id());
        return m.openEnded() ? s.finished() : s.done() >= m.effectiveTotal();
    }

    /** First step that is not done; empty when the whole pattern is finished. */
    public Optional<StepMeta> current() {
        return steps.stream().filter(m -> !isDone(m)).findFirst();
    }

    public List<Change> advance(UUID stepId) {
        StepMeta m = meta(stepId);
        if (isDone(m)) throw new ConflictException("step is already done");
        StepState s = state(stepId);
        return List.of(set(new StepState(stepId, s.done() + 1, false)));
    }

    public List<Change> retreat(UUID stepId) {
        StepState s = state(stepId);
        meta(stepId);
        if (s.finished()) return List.of(set(new StepState(stepId, s.done(), false)));
        if (s.done() == 0) throw new ConflictException("nothing to take back on this step");
        return List.of(set(new StepState(stepId, s.done() - 1, false)));
    }

    public List<Change> finish(UUID stepId) {
        StepMeta m = meta(stepId);
        if (!m.openEnded()) throw new ConflictException("only open-ended steps are finished by hand");
        if (isDone(m)) throw new ConflictException("step is already finished");
        return List.of(set(new StepState(stepId, state(stepId).done(), true)));
    }

    /**
     * 拆线: go back to {@code round} (0-based) of a step and clear everything after it.
     * Open-ended steps keep their round count and just become unfinished.
     */
    public List<Change> rewind(UUID stepId, int round) {
        StepMeta m = meta(stepId);
        StepState s = state(stepId);
        List<Change> changes = new ArrayList<>();
        StepState target;
        if (m.openEnded()) {
            target = new StepState(stepId, s.done(), false);
        } else {
            if (round < 0 || round >= m.effectiveTotal()) throw new NotFoundException("round");
            if (round > s.done()) throw new ConflictException("cannot rewind forward past current progress");
            target = new StepState(stepId, round, false);
        }
        if (!target.equals(s)) changes.add(set(target));
        boolean after = false;
        for (StepMeta x : steps) {
            if (after) {
                StepState xs = state(x.id());
                if (xs.done() != 0 || xs.finished()) changes.add(set(StepState.empty(x.id())));
            }
            if (x.id().equals(stepId)) after = true;
        }
        if (changes.isEmpty()) throw new ConflictException("nothing to rewind");
        return changes;
    }

    /** Applies previously recorded "before" states (undo). */
    public List<Change> restore(List<StepState> before) {
        return before.stream().map(this::set).toList();
    }

    public double percent() {
        int units = 0;
        int doneUnits = 0;
        for (StepMeta m : steps) {
            StepState s = state(m.id());
            if (m.openEnded()) {
                units += 1;
                doneUnits += s.finished() ? 1 : 0;
            } else {
                units += m.effectiveTotal();
                doneUnits += Math.min(s.done(), m.effectiveTotal());
            }
        }
        return units == 0 ? 0 : Math.round(doneUnits * 1000.0 / units) / 10.0;
    }

    public long doneSteps() {
        return steps.stream().filter(this::isDone).count();
    }

    private StepMeta meta(UUID stepId) {
        return steps.stream().filter(m -> m.id().equals(stepId)).findFirst()
                .orElseThrow(() -> new NotFoundException("step"));
    }

    private Change set(StepState after) {
        StepState before = state(after.stepId());
        states.put(after.stepId(), after);
        return new Change(before, after);
    }
}
