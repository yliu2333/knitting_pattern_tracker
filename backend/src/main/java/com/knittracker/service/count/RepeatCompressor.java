package com.knittracker.service.count;

import java.util.ArrayList;
import java.util.List;

/** Folds periodic runs into REPEATs: k11, sm, k11, sm, k11 → (k11, sm) × 2, k11. */
final class RepeatCompressor {

    private RepeatCompressor() {}

    static List<ResolvedSegment> compress(List<ResolvedSegment> seq) {
        List<ResolvedSegment> out = new ArrayList<>();
        int i = 0;
        while (i < seq.size()) {
            int bestPeriod = 0;
            int bestReps = 0;
            for (int p = 1; i + 2 * p <= seq.size(); p++) {
                int reps = 1;
                while (i + (reps + 1) * p <= seq.size()
                        && seq.subList(i + reps * p, i + (reps + 1) * p).equals(seq.subList(i, i + p))) {
                    reps++;
                }
                if (reps >= 2 && p * reps > bestPeriod * bestReps) {
                    bestPeriod = p;
                    bestReps = reps;
                }
            }
            if (bestReps >= 2) {
                out.add(ResolvedSegment.repeat(bestReps, seq.subList(i, i + bestPeriod)));
                i += bestPeriod * bestReps;
            } else {
                out.add(seq.get(i++));
            }
        }
        return out;
    }
}
