package com.knittracker.service.count;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.knittracker.ir.Fixtures;
import com.knittracker.ir.PatternIr;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

/** Counts the pattern states ("(48 sts)", "36目になる") are copied into the IR and checked by the simulator. */
class StatedCountsTest {

    ObjectNode hat() throws Exception {
        return (ObjectNode) Fixtures.MAPPER.readTree(Files.readString(Fixtures.PUBLIC_DIR.resolve("sample-hat.ir.json")));
    }

    PatternIr ir(ObjectNode n) throws Exception {
        return Fixtures.MAPPER.treeToValue(n, PatternIr.class);
    }

    @Test
    void matchingStatedStepCountPasses() throws Exception {
        ObjectNode n = hat();
        ((ObjectNode) n.at("/sections/2/steps/0")).put("statedEndSts", 48);
        assertThat(StitchSimulator.simulate(ir(n)).steps().get(4).endSts()).isEqualTo(48);
    }

    @Test
    void wrongStatedStepCountFailsWithBothNumbers() throws Exception {
        ObjectNode n = hat();
        ((ObjectNode) n.at("/sections/2/steps/0")).put("statedEndSts", 50);
        assertThatThrownBy(() -> StitchSimulator.simulate(ir(n)))
                .hasMessageContaining("says this step ends with 50").hasMessageContaining("gives 48");
    }

    @Test
    void verifyCountSegmentWithAStatedNumberIsChecked() throws Exception {
        ObjectNode n = hat();
        n.withArray("/sections/2/steps/1/rows/0/segments").addObject().put("code", "VERIFY_COUNT").put("count", 25);
        assertThatThrownBy(() -> StitchSimulator.simulate(ir(n))).hasMessageContaining("says 25 sts here");
    }
}
