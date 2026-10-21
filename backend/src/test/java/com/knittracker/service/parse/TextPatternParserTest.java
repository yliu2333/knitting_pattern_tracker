package com.knittracker.service.parse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.knittracker.ir.Fixtures;
import com.knittracker.ir.PatternIr;
import com.knittracker.ir.PatternIr.SourceType;
import com.knittracker.service.parse.PatternParser.ParseOptions;
import com.knittracker.service.parse.PatternParser.SourceFile;
import com.knittracker.service.llm.LlmTiers;
import com.knittracker.service.llm.LlmClient.Attachment;
import java.util.List;
import com.knittracker.service.technique.TechniqueLibrary;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

class TextPatternParserTest {

    final ObjectMapper mapper = new ObjectMapper();
    final String hatJson = read("sample-hat.ir.json");
    final SourceFile text = new SourceFile("hat.txt", "text/plain", "k1, p1 ...".getBytes(StandardCharsets.UTF_8));

    TextPatternParser parser(FakeLlm llm) {
        return new TextPatternParser(llm, mapper, "test-model");
    }

    @Test
    void validOutputIsAcceptedOnTheFirstAttemptEvenInsideFences() {
        FakeLlm llm = new FakeLlm().reply("```json\n" + hatJson + "\n```");
        PatternParser.ParsedPattern p = parser(llm).parse(text, SourceType.TEXT_PDF, new ParseOptions(null), TechniqueLibrary.builtin());
        assertThat(p.attempts()).isEqualTo(1);
        assertThat(p.ir()).isEqualTo(Fixtures.read(Fixtures.PUBLIC_DIR.resolve("sample-hat.ir.json")));
    }

    @Test
    void invalidJsonIsRetriedWithTheErrorAndPreviousOutput() {
        FakeLlm llm = new FakeLlm().reply("{not json", hatJson);
        PatternParser.ParsedPattern p = parser(llm).parse(text, SourceType.TEXT_PDF, new ParseOptions(null), TechniqueLibrary.builtin());
        assertThat(p.attempts()).isEqualTo(2);
        assertThat(llm.calls.get(1).user()).contains("previous output was rejected").contains("{not json");
    }

    @Test
    void stitchArithmeticErrorsFromTheLlmAreCaughtBySimulationAndFedBack() throws Exception {
        ObjectNode bad = (ObjectNode) mapper.readTree(hatJson);
        // rib written as a literal 200 stitches on a 96-stitch hat: only the simulator can notice
        ((ObjectNode) bad.at("/sections/0/steps/1/rows/0/segments/0")).removeAll()
                .put("code", "K").put("count", 200);
        FakeLlm llm = new FakeLlm().reply(bad.toString(), hatJson);
        PatternParser.ParsedPattern p = parser(llm).parse(text, SourceType.TEXT_PDF, new ParseOptions(null), TechniqueLibrary.builtin());
        assertThat(p.attempts()).isEqualTo(2);
        assertThat(llm.calls.get(1).user()).contains("stitch simulation failed").contains("sections[0].steps[1]");
    }

    @Test
    void givesUpAfterThreeAttemptsWithTheLastErrors() {
        FakeLlm llm = new FakeLlm().reply("[]", "[]", "{\"irVersion\":1}");
        assertThatThrownBy(() -> parser(llm).parse(text, SourceType.TEXT_PDF, new ParseOptions(null), TechniqueLibrary.builtin()))
                .isInstanceOf(ParseFailedException.class)
                .satisfies(e -> assertThat(((ParseFailedException) e).errors()).contains("title is required"));
        assertThat(llm.calls).hasSize(3);
    }

    @Test
    void javaDoesTheArithmeticTheLlmMayNot() throws Exception {
        ObjectNode ir = (ObjectNode) mapper.readTree(hatJson);
        ((ObjectNode) ir.at("/sections/2/steps/0")).remove("total");               // REPEAT 6 × 2
        ((ObjectNode) ir.at("/sections/1/steps/0/target")).removeAll().put("lengthIn", 6).put("from", "CAST_ON");
        PatternIr out = parser(new FakeLlm().reply(ir.toString()))
                .parse(text, SourceType.TEXT_PDF, new ParseOptions(null), TechniqueLibrary.builtin()).ir();
        assertThat(out.sections().get(2).steps().get(0).total()).isEqualTo(12);
        assertThat(out.sections().get(1).steps().get(0).target().get("lengthCm").asDouble()).isEqualTo(15.0);
    }

    @Test
    void promptCarriesSchemaGlossarySizeAndTextInline() {
        FakeLlm llm = new FakeLlm().reply(hatJson);
        parser(llm).parse(text, SourceType.TEXT_PDF, new ParseOptions("M"), TechniqueLibrary.builtin());
        FakeLlm.Call call = llm.calls.getFirst();
        assertThat(call.system()).contains("NEVER do arithmetic").contains("左上2目一度").contains("左上2并1");
        assertThat(call.user()).contains("Size to extract: M").contains("<pattern>\nk1, p1 ...");
        assertThat(call.attachments()).isEmpty();
    }

    @Test
    void pdfAndImagesGoAsAttachmentsAndSourceTypeIsOurs() {
        FakeLlm llm = new FakeLlm().reply(hatJson.replace("TEXT_PDF", "JP_KNIT_CHART"));
        SourceFile pdf = new SourceFile("p.pdf", "application/pdf", new byte[] {1, 2, 3});
        PatternIr ir = parser(llm).parse(pdf, SourceType.TEXT_IMAGE, new ParseOptions(null), TechniqueLibrary.builtin()).ir();
        assertThat(llm.calls.getFirst().attachments()).singleElement()
                .satisfies(a -> assertThat(a.mimeType()).isEqualTo("application/pdf"));
        assertThat(ir.sourceType()).isEqualTo(SourceType.TEXT_IMAGE);
    }

    @Test
    void unsupportedFileTypeIsRejectedBeforeAnyLlmCall() {
        FakeLlm llm = new FakeLlm();
        assertThatThrownBy(() -> parser(llm).parse(new SourceFile("x.zip", "application/zip", new byte[0]),
                SourceType.TEXT_PDF, new ParseOptions(null), TechniqueLibrary.builtin())).isInstanceOf(IllegalArgumentException.class);
        assertThat(llm.calls).isEmpty();
    }

    // ---------- model chain: cheapest first, escalate only on failure ----------

    TextPatternParser chain(FakeLlm cheap, FakeLlm strong) {
        return new TextPatternParser(new LlmTiers(List.of(new LlmTiers.Tier("lite", cheap),
                new LlmTiers.Tier("flash", strong))), mapper);
    }

    @Test
    void cheapModelThatSucceedsIsTheOnlyOneCalled() {
        FakeLlm cheap = new FakeLlm().reply(hatJson);
        FakeLlm strong = new FakeLlm();
        PatternParser.ParsedPattern p = chain(cheap, strong).parse(text, SourceType.TEXT_PDF, new ParseOptions(null), TechniqueLibrary.builtin());
        assertThat(p.model()).isEqualTo("lite");
        assertThat(strong.calls).isEmpty();
    }

    @Test
    void escalatesAfterTheCheapModelUsesItsAttempts() {
        FakeLlm cheap = new FakeLlm().reply("{bad", "{bad");
        FakeLlm strong = new FakeLlm().reply(hatJson);
        PatternParser.ParsedPattern p = chain(cheap, strong).parse(text, SourceType.TEXT_PDF, new ParseOptions(null), TechniqueLibrary.builtin());
        assertThat(cheap.calls).hasSize(TextPatternParser.ATTEMPTS_PER_TIER);
        assertThat(p.model()).isEqualTo("flash");
        assertThat(strong.calls.getFirst().user()).doesNotContain("previous output was rejected");   // fresh start
    }

    @Test
    void unavailableCheapModelHandsOverImmediately() {
        FakeLlm cheap = new FakeLlm() {
            @Override
            public String completeJson(String s, String u, List<Attachment> a) {
                calls.add(new Call(s, u, a));
                throw new com.knittracker.service.llm.LlmException("Gemini HTTP 429");
            }
        };
        FakeLlm strong = new FakeLlm().reply(hatJson);
        PatternParser.ParsedPattern p = chain(cheap, strong).parse(text, SourceType.TEXT_PDF, new ParseOptions(null), TechniqueLibrary.builtin());
        assertThat(cheap.calls).hasSize(1);
        assertThat(p.model()).isEqualTo("flash");
    }

    @Test
    void whenEveryModelFailsTheErrorsOfEachAreReported() {
        FakeLlm cheap = new FakeLlm().reply("[]", "[]");
        FakeLlm strong = new FakeLlm().reply("[]", "{\"irVersion\":1}");
        assertThatThrownBy(() -> chain(cheap, strong).parse(text, SourceType.TEXT_PDF, new ParseOptions(null), TechniqueLibrary.builtin()))
                .isInstanceOf(ParseFailedException.class)
                .satisfies(e -> assertThat(((ParseFailedException) e).errors())
                        .anyMatch(x -> x.startsWith("lite:")).anyMatch(x -> x.startsWith("flash:") && x.contains("title is required")));
    }

    @Test
    void versionNamesTheWholeChainSoChangingItInvalidatesTheCache() {
        assertThat(chain(new FakeLlm(), new FakeLlm()).version()).isEqualTo("text-v3/lite+flash");
    }

    static String read(String name) {
        try {
            return Files.readString(Fixtures.PUBLIC_DIR.resolve(name));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
