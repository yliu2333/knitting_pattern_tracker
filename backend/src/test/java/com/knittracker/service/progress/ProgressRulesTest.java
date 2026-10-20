package com.knittracker.service.progress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knittracker.ir.StepIr.Kind;
import com.knittracker.service.ConflictException;
import com.knittracker.service.NotFoundException;
import com.knittracker.service.progress.ProgressRules.Change;
import com.knittracker.service.progress.ProgressRules.StepMeta;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProgressRulesTest {

    final StepMeta castOn = new StepMeta(UUID.randomUUID(), Kind.CHECKPOINT, null);
    final StepMeta rib = new StepMeta(UUID.randomUUID(), Kind.ROWS, 3);
    final StepMeta body = new StepMeta(UUID.randomUUID(), Kind.ROWS, null);
    final StepMeta crown = new StepMeta(UUID.randomUUID(), Kind.REPEAT, 4);
    final List<StepMeta> steps = List.of(castOn, rib, body, crown);

    ProgressRules fresh() {
        return new ProgressRules(steps, Map.of());
    }

    @Test
    void currentStepMovesAsStepsComplete() {
        ProgressRules r = fresh();
        assertThat(r.current()).contains(castOn);
        r.advance(castOn.id());
        assertThat(r.current()).contains(rib);
        for (int i = 0; i < 3; i++) r.advance(rib.id());
        assertThat(r.current()).contains(body);
        assertThatThrownBy(() -> r.advance(rib.id())).isInstanceOf(ConflictException.class);
    }

    @Test
    void openEndedStepCountsRoundsUntilFinishedByHand() {
        ProgressRules r = fresh();
        r.advance(body.id());
        r.advance(body.id());
        assertThat(r.state(body.id()).done()).isEqualTo(2);
        assertThat(r.isDone(body)).isFalse();
        r.finish(body.id());
        assertThat(r.isDone(body)).isTrue();
        assertThatThrownBy(() -> r.finish(body.id())).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> r.finish(rib.id())).isInstanceOf(ConflictException.class);
    }

    @Test
    void retreatUnfinishesBeforeTakingRoundsBack() {
        ProgressRules r = fresh();
        r.advance(body.id());
        r.finish(body.id());
        r.retreat(body.id());
        assertThat(r.state(body.id())).isEqualTo(new StepState(body.id(), 1, false));
        r.retreat(body.id());
        assertThat(r.state(body.id()).done()).isZero();
        assertThatThrownBy(() -> r.retreat(body.id())).isInstanceOf(ConflictException.class);
    }

    @Test
    void rewindClearsEverythingAfterAndReportsBeforeStates() {
        ProgressRules r = fresh();
        r.advance(castOn.id());
        for (int i = 0; i < 3; i++) r.advance(rib.id());
        r.advance(body.id());
        r.finish(body.id());
        r.advance(crown.id());

        List<Change> changes = r.rewind(rib.id(), 1);

        assertThat(r.state(rib.id()).done()).isEqualTo(1);
        assertThat(r.state(body.id())).isEqualTo(StepState.empty(body.id()));
        assertThat(r.state(crown.id())).isEqualTo(StepState.empty(crown.id()));
        assertThat(r.state(castOn.id()).done()).isEqualTo(1);
        assertThat(r.current()).contains(rib);

        r.restore(changes.stream().map(Change::before).toList());
        assertThat(r.state(rib.id()).done()).isEqualTo(3);
        assertThat(r.state(body.id())).isEqualTo(new StepState(body.id(), 1, true));
        assertThat(r.state(crown.id()).done()).isEqualTo(1);
    }

    @Test
    void rewindRejectsRoundsOutsideTheStepOrAheadOfProgress() {
        ProgressRules r = fresh();
        r.advance(castOn.id());
        assertThatThrownBy(() -> r.rewind(rib.id(), 3)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> r.rewind(rib.id(), 2)).isInstanceOf(ConflictException.class);
    }

    @Test
    void unknownStepIsNotFound() {
        assertThatThrownBy(() -> fresh().advance(UUID.randomUUID())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void percentCountsRoundsLikeThePrototype() {
        ProgressRules r = fresh();
        assertThat(r.percent()).isZero();
        r.advance(castOn.id());
        r.advance(rib.id());
        // units: 1 + 3 + 1 + 4 = 9, done 2
        assertThat(r.percent()).isEqualTo(22.2);
    }
}
