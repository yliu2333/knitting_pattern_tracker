package com.knittracker.service.count;

import static org.assertj.core.api.Assertions.assertThat;

import com.knittracker.ir.Fixtures;
import com.knittracker.ir.PatternIr;
import com.knittracker.service.count.PatternPlan.StepPlan;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Split-and-rejoin body, flat rows, picked-up sleeve, "to N before BOR" — on a tiny made-up pattern:
 * 20 sts round → hold 10 (front), work back flat → put back aside → front flat → rejoin with
 * 2 + 2 underarm sts (10 back + 8 front + 4 = 22) → sleeve picked up 2 + 5 + 1 + 4 + 2 = 14 → 12 → 6.
 */
class StitchSimulatorPiecesTest {

    static final String IR = """
            {"irVersion":1,"title":"Mini top","craft":"KNIT","sourceType":"TEXT_PDF","sourceLang":"en","sections":[
             {"nameSrc":"Yoke","steps":[
              {"kind":"CHECKPOINT","titleSrc":"CO","rows":[{"segments":[{"code":"CO_PROVISIONAL","count":20},{"code":"PM","marker":"BOR"},{"code":"JOIN_ROUND"}]}]},
              {"kind":"ROWS","unit":"ROUND","titleSrc":"set up","total":1,"rows":[{"segments":[{"code":"K","count":5},{"code":"PM"},{"code":"K","count":10},{"code":"PM"},{"code":"K","count":5}]}]},
              {"kind":"CHECKPOINT","titleSrc":"fold","rows":[{"segments":[{"code":"REPEAT","until":"END","children":[{"code":"FOLD_JOIN"}]}]}]}]},
             {"nameSrc":"Back and front","steps":[
              {"kind":"ROWS","unit":"ROW","titleSrc":"split","total":1,"rows":[{"segments":[{"code":"K","until":"MARKER"},{"code":"HOLD","count":10,"attrs":{"piece":"FRONT"}},{"code":"TURN"}]}]},
              {"kind":"REPEAT","unit":"ROW","titleSrc":"back","total":4,"groupCount":2,"rowsPerGroup":2,"rows":[{"segments":[{"code":"P","until":"END"}]},{"segments":[{"code":"K","until":"END"}]}]},
              {"kind":"CHECKPOINT","titleSrc":"back aside","rows":[{"segments":[{"code":"PUT_ASIDE","attrs":{"piece":"BACK"}}]}]},
              {"kind":"ROWS","unit":"ROW","titleSrc":"front","total":1,"rows":[{"segments":[{"code":"PICKUP_HELD","attrs":{"piece":"FRONT"}},{"code":"HOLD","count":1,"attrs":{"piece":"SH"}},{"code":"K","until":"END","stopBefore":1},{"code":"HOLD","count":1,"attrs":{"piece":"SH2"}},{"code":"TURN"}]}]},
              {"kind":"REPEAT","unit":"ROW","titleSrc":"front rows","total":2,"groupCount":1,"rowsPerGroup":2,"rows":[{"segments":[{"code":"P","until":"END"}]},{"segments":[{"code":"K","until":"END"}]}]},
              {"kind":"ROWS","unit":"ROW","titleSrc":"rejoin","total":1,"rows":[{"segments":[{"code":"P","until":"END"},{"code":"TURN"}]}]},
              {"kind":"ROWS","unit":"ROW","titleSrc":"rejoin 2","total":1,"rows":[{"segments":[{"code":"K","until":"END"},{"code":"CO_CABLE","count":2},{"code":"JOIN_PIECE","attrs":{"piece":"BACK"}},{"code":"K","until":"END"},{"code":"CO_CABLE","count":2},{"code":"PM","marker":"BOR"},{"code":"JOIN_ROUND"}]}]},
              {"kind":"ROWS","unit":"ROUND","titleSrc":"body","total":2,"rows":[{"segments":[{"code":"K","until":"END"}]}]},
              {"kind":"CHECKPOINT","titleSrc":"bo","rows":[{"segments":[{"code":"BO"}]}]}]},
             {"nameSrc":"Sleeve","steps":[
              {"kind":"CHECKPOINT","titleSrc":"pick up","rows":[{"segments":[{"code":"PICKUP_EDGE","count":2},{"code":"PICKUP_EDGE","count":5},{"code":"JOIN_PIECE","attrs":{"piece":"SH"}},{"code":"K","count":1},{"code":"PICKUP_EDGE","count":4},{"code":"PICKUP_EDGE","count":2},{"code":"PM","marker":"BOR"}]}]},
              {"kind":"ROWS","unit":"ROUND","titleSrc":"dec","total":1,"rows":[{"segments":[{"code":"K","count":1},{"code":"SSK"},{"code":"K","until":"BOR","stopBefore":3},{"code":"K2TOG"},{"code":"K","count":1}]}]},
              {"kind":"ROWS","unit":"ROUND","titleSrc":"cuff","total":1,"rows":[{"segments":[{"code":"REPEAT","until":"BOR","stopBefore":0,"children":[{"code":"K2TOG"}]}]}]}]}]}
            """;

    final PatternIr ir = read();
    final List<StepPlan> steps = StitchSimulator.simulate(ir).steps();

    @Test
    void stitchCountsThroughSplitRejoinAndSleeve() {
        assertThat(steps.stream().map(StepPlan::endSts).toList())
                .containsExactly(20, 20, 20, 10, 10, 0, 8, 8, 8, 22, 22, 0, 14, 12, 6);
    }

    @Test
    void foldJoinCheckpointOnAJoinedRoundIsWorkedAsARound() {
        assertThat(steps.get(2).rounds().getFirst().segments().getFirst().times()).isEqualTo(20);
    }

    @Test
    void afterTurningAtTheHoldGapRowsGoEdgeToEdgeAcrossBor() {
        // back = 5 sts each side of BOR; flat rows cross BOR but end at the edges
        var row = steps.get(4).rounds().getFirst().segments();
        assertThat(row).extracting(ResolvedSegment::code).containsExactly("P", "SM", "P");
        assertThat(row).extracting(ResolvedSegment::count).containsExactly(5, 0, 5);
    }

    @Test
    void rejoinedBodyIsOneRoundFromTheNewBor() {
        assertThat(steps.get(10).rounds().getFirst().sectionsAtStart()).containsExactly(22);
    }

    @Test
    void sleeveDecreasesStopTheStatedNumberBeforeBor() {
        var row = steps.get(13).rounds().getFirst().segments();
        assertThat(row).extracting(ResolvedSegment::count).containsExactly(1, 1, 8, 1, 1);
    }

    /** As the LLM wrote it for Sheer na Knit: front ends on a WS row, then the rejoin is itself a ROUND step. */
    @Test
    void rejoinWrittenAsARoundAfterAWrongSideRowWorks() throws Exception {
        String asRound = IR.replace(
                "{\"kind\":\"ROWS\",\"unit\":\"ROW\",\"titleSrc\":\"rejoin\",\"total\":1,\"rows\":[{\"segments\":[{\"code\":\"P\",\"until\":\"END\"},{\"code\":\"TURN\"}]}]},",
                "{\"kind\":\"ROWS\",\"unit\":\"ROW\",\"titleSrc\":\"ws\",\"total\":1,\"rows\":[{\"segments\":[{\"code\":\"P\",\"until\":\"END\"}]}]},")
                .replace("\"unit\":\"ROW\",\"titleSrc\":\"rejoin 2\"", "\"unit\":\"ROUND\",\"titleSrc\":\"rejoin 2\"")
                .replace(",{\"code\":\"PM\",\"marker\":\"BOR\"},{\"code\":\"JOIN_ROUND\"}]}]},", ",{\"code\":\"PM\",\"marker\":\"BOR\"}]}]},");
        assertThat(asRound).isNotEqualTo(IR);
        List<StepPlan> s = StitchSimulator.simulate(Fixtures.MAPPER.readValue(asRound, PatternIr.class)).steps();
        assertThat(s.get(9).endSts()).isEqualTo(22);
        assertThat(s.get(10).rounds().getFirst().sectionsAtStart()).containsExactly(22);
    }

    static PatternIr read() {
        try {
            return Fixtures.MAPPER.readValue(IR, PatternIr.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
