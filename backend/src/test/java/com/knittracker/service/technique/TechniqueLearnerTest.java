package com.knittracker.service.technique;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knittracker.ir.Fixtures;
import com.knittracker.ir.PatternIr;
import com.knittracker.service.count.PatternPlan;
import com.knittracker.service.count.StitchSimulator;
import com.knittracker.service.parse.FakeLlm;
import java.util.List;
import org.junit.jupiter.api.Test;

class TechniqueLearnerTest {

    final ObjectMapper mapper = new ObjectMapper();
    final TechniqueLibrary builtin = TechniqueLibrary.builtin();

    /** 10 sts in the round, then one round of the given segment repeated to the end. */
    static PatternIr pattern(String segmentJson, Integer statedEnd) {
        String stated = statedEnd == null ? "" : ",\"statedEndSts\":" + statedEnd;
        String json = """
                {"irVersion":1,"title":"t","craft":"KNIT","sourceType":"TEXT_PDF","sourceLang":"en","sections":[{"nameSrc":"s","steps":[
                 {"kind":"CHECKPOINT","titleSrc":"co","rows":[{"segments":[{"code":"CO","count":10},{"code":"PM","marker":"BOR"},{"code":"JOIN_ROUND"}]}]},
                 {"kind":"ROWS","unit":"ROUND","titleSrc":"r","total":1%s,"rows":[{"segments":[{"code":"REPEAT","until":"END","children":[%s]}]}]}]}]}
                """.formatted(stated, segmentJson);
        try {
            return Fixtures.MAPPER.readValue(json, PatternIr.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static final String KFB = """
            {"terms":[{"term":"kfb","technique":{"code":"KFB","kind":"STITCH",
              "def":{"consumes":1,"produces":2,"worked":"KNIT","source":"LIVE"},
              "names":{"zh":"前后加针","en":"knit front and back","ja":"編み出し増し目"},
              "templates":{"zh":"前后加针","en":"kfb"},"aliases":{"en":["kfb","knit front and back"]}}}]}""";

    int end(TechniqueLearner.Result r) {
        List<PatternPlan.StepPlan> steps = StitchSimulator.simulate(r.ir(), r.library()).steps();
        return steps.getLast().endSts();
    }

    @Test
    void knownNameInAnyLanguageResolvesWithoutCallingTheLlm() {
        FakeLlm llm = new FakeLlm();
        var r = new TechniqueLearner(llm, mapper).learn(pattern("{\"code\":\"UNKNOWN\",\"raw\":\"左上2目一度\"}", null), builtin);
        assertThat(llm.calls).isEmpty();
        assertThat(end(r)).isEqualTo(5);
    }

    @Test
    void newTechniqueIsLearnedAndUsed() {
        FakeLlm llm = new FakeLlm().reply(KFB);
        var r = new TechniqueLearner(llm, mapper).learn(pattern("{\"code\":\"UNKNOWN\",\"raw\":\"kfb\"}", 20), builtin);
        assertThat(r.learned()).extracting(Technique::code).containsExactly("KFB");
        assertThat(end(r)).isEqualTo(20);
        assertThat(llm.calls.getFirst().user()).contains("term: kfb").contains("K2TOG");   // library listed
    }

    @Test
    void anotherNameForALearnedTechniqueBecomesAnAliasNotADuplicate() {
        TechniqueLibrary withKfb = new TechniqueLearner(new FakeLlm().reply(KFB), mapper)
                .learn(pattern("{\"code\":\"UNKNOWN\",\"raw\":\"kfb\"}", null), builtin).library();
        // The LLM wrongly claims a brand-new technique, but its structure equals KFB's.
        FakeLlm llm = new FakeLlm().reply("""
                {"terms":[{"term":"inc1","technique":{"code":"INC1","kind":"STITCH",
                  "def":{"consumes":1,"produces":2,"worked":"KNIT","source":"LIVE"},
                  "names":{"zh":"加一针","en":"increase 1"},"templates":{"zh":"加一针","en":"inc1"}}}]}""");
        var r = new TechniqueLearner(llm, mapper).learn(pattern("{\"code\":\"UNKNOWN\",\"raw\":\"inc1\"}", null), withKfb);
        assertThat(r.learned()).isEmpty();
        assertThat(r.aliases()).containsExactly(new LearnedAlias("inc1", "en", "KFB"));
        assertThat(end(r)).isEqualTo(20);
    }

    @Test
    void newTechniqueWithTheStructureOfABuiltinOneIsThatBuiltin() {
        FakeLlm llm = new FakeLlm().reply("""
                {"terms":[{"term":"dec","technique":{"code":"DEC","kind":"STITCH",
                  "def":{"consumes":2,"produces":1,"worked":"KNIT","lean":"RIGHT"},
                  "names":{"zh":"减针","en":"decrease"},"templates":{"zh":"减针","en":"dec"}}}]}""");
        var r = new TechniqueLearner(llm, mapper).learn(pattern("{\"code\":\"UNKNOWN\",\"raw\":\"dec\"}", null), builtin);
        assertThat(r.aliases()).containsExactly(new LearnedAlias("dec", "en", "K2TOG"));
        assertThat(r.learned()).isEmpty();
    }

    @Test
    void aliasOfAnswerMapsTheTerm() {
        FakeLlm llm = new FakeLlm().reply("{\"terms\":[{\"term\":\"skp\",\"aliasOf\":\"SSK\"}]}");
        var r = new TechniqueLearner(llm, mapper).learn(pattern("{\"code\":\"UNKNOWN\",\"raw\":\"skp\"}", null), builtin);
        assertThat(r.aliases()).containsExactly(new LearnedAlias("skp", "en", "SSK"));
        assertThat(end(r)).isEqualTo(5);
    }

    @Test
    void anExistingNameWithADifferentStructureIsRejected() {
        FakeLlm llm = new FakeLlm().reply("""
                {"terms":[{"term":"weird","technique":{"code":"WEIRD","kind":"STITCH",
                  "def":{"consumes":3,"produces":1,"worked":"KNIT","lean":"RIGHT"},
                  "names":{"zh":"左上2并1","en":"k3tog"},"templates":{"zh":"x","en":"x"}}}]}""");
        var r = new TechniqueLearner(llm, mapper).learn(pattern("{\"code\":\"UNKNOWN\",\"raw\":\"weird\"}", null), builtin);
        assertThat(r.learned()).isEmpty();
        assertThat(r.unsupported()).singleElement().asString().contains("already means K2TOG");
    }

    @Test
    void invalidDefinitionsAreNotLearned() {
        FakeLlm llm = new FakeLlm().reply("""
                {"terms":[{"term":"bad","technique":{"code":"bad code","kind":"PRIMITIVE","def":{"consumes":-1},
                  "names":{"en":"bad"},"templates":{"en":"{weird}"}}}]}""");
        var r = new TechniqueLearner(llm, mapper).learn(pattern("{\"code\":\"UNKNOWN\",\"raw\":\"bad\"}", null), builtin);
        assertThat(r.learned()).isEmpty();
        assertThat(r.unsupported()).singleElement().asString()
                .contains("UPPER_SNAKE_CASE").contains("cannot be learned").contains("names.zh missing");
    }

    @Test
    void cableDefinitionIsCheckedForConsistency() {
        FakeLlm llm = new FakeLlm().reply("""
                {"terms":[{"term":"C4F","technique":{"code":"C4F","kind":"STITCH",
                  "def":{"consumes":4,"produces":4,"worked":"KNIT","cross":{"front":2,"back":1,"dir":"LEFT"}},
                  "names":{"zh":"左上2×2交叉","en":"cable 4 front"},"templates":{"zh":"左上2×2交叉","en":"C4F"}}}]}""");
        var r = new TechniqueLearner(llm, mapper).learn(pattern("{\"code\":\"UNKNOWN\",\"raw\":\"C4F\"}", null), builtin);
        assertThat(r.unsupported()).singleElement().asString().contains("front + back");
    }

    @Test
    void macroOfExistingCodesIsLearned() {
        FakeLlm llm = new FakeLlm().reply("""
                {"terms":[{"term":"k2tog twice","technique":{"code":"DOUBLE_K2TOG","kind":"MACRO",
                  "def":{"steps":[{"code":"K2TOG"},{"code":"K2TOG"}]},
                  "names":{"zh":"连续两次左上2并1","en":"k2tog twice"},"templates":{"zh":"两次左上2并1","en":"(k2tog) twice"}}}]}""");
        var r = new TechniqueLearner(llm, mapper).learn(pattern("{\"code\":\"UNKNOWN\",\"raw\":\"k2tog twice\"}", null), builtin);
        assertThat(r.learned()).extracting(Technique::code).containsExactly("DOUBLE_K2TOG");
        assertThat(r.library().byCode("DOUBLE_K2TOG")).isPresent();
    }

    @Test
    void anUnknownCodeTheLlmInventedIsTreatedAsATerm() {
        FakeLlm llm = new FakeLlm().reply(KFB);
        var r = new TechniqueLearner(llm, mapper).learn(pattern("{\"code\":\"kfb\"}", null), builtin);
        assertThat(end(r)).isEqualTo(20);
    }
}
