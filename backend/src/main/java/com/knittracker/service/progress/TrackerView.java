package com.knittracker.service.progress;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;

/** Everything the tracker screen needs except per-round instruction text (see {@link RoundView}). */
public record TrackerView(
        UUID projectId,
        UUID patternId,
        String title,
        UUID currentStepId,
        long doneSteps,
        int totalSteps,
        double percent,
        LastAction lastAction,
        List<SectionView> sections) {

    public record SectionView(String name, List<StepView> steps) {}

    public record StepView(
            UUID id,
            String title,
            String kind,
            String unit,
            Integer total,
            Integer groupCount,
            Integer rowsPerGroup,
            Double needleMm,
            JsonNode target,
            int startSts,
            int endSts,
            int done,
            boolean finished,
            boolean isDone,
            String note) {}

    /** Most recent undoable action; the client words it ("+1 圈 · 短行 3–4"). */
    public record LastAction(String type, UUID stepId, Integer round) {}

    /** Instruction for one round of one step, with literal counts. {@code round} is 0-based. */
    public record RoundView(
            UUID stepId,
            int round,
            Integer total,
            String rowName,
            String side,
            String text,
            int stsAfter,
            List<Integer> sectionsAtStart,
            String note) {}
}
