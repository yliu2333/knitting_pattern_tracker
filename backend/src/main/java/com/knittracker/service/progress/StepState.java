package com.knittracker.service.progress;

import java.util.UUID;

/** Progress of one step: rounds done, and for open-ended steps whether the knitter marked it finished. */
public record StepState(UUID stepId, int done, boolean finished) {

    public static StepState empty(UUID stepId) {
        return new StepState(stepId, 0, false);
    }
}
