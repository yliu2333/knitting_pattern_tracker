package com.knittracker.service.count;

import java.util.List;

/** Output of {@link StitchSimulator}: literal instructions and stitch counts for every round of every step. */
public record PatternPlan(List<List<StepPlan>> sections) {

    /**
     * {@code rounds} has one entry per round; an open-ended step has a single representative round.
     */
    public record StepPlan(int startSts, int endSts, List<RoundPlan> rounds) {}

    /**
     * @param sectionsAtStart stitch counts between markers from BOR at the start of the round
     *                        (empty when the piece has no BOR yet)
     */
    public record RoundPlan(int rowOrd, List<ResolvedSegment> segments, int stsAfter, List<Integer> sectionsAtStart) {}

    public List<StepPlan> steps() {
        return sections.stream().flatMap(List::stream).toList();
    }
}
