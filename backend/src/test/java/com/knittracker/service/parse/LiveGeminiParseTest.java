package com.knittracker.service.parse;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knittracker.ir.Fixtures;
import com.knittracker.ir.PatternIr;
import com.knittracker.ir.PatternIr.SourceType;
import com.knittracker.service.count.PatternPlan;
import com.knittracker.service.count.StitchSimulator;
import com.knittracker.service.llm.GeminiClient;
import com.knittracker.service.parse.PatternParser.ParseOptions;
import com.knittracker.service.parse.PatternParser.SourceFile;
import java.net.http.HttpClient;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Real Gemini call (only with GEMINI_API_KEY). Judged by stitch counts, not by exact JSON. */
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
class LiveGeminiParseTest {

    @Test
    void englishTextHatParsesToTheSameStitchCountsAsTheHandWrittenIr() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String model = System.getenv().getOrDefault("GEMINI_MODEL", "gemini-2.5-flash");
        var llm = new GeminiClient(HttpClient.newHttpClient(), mapper,
                "https://generativelanguage.googleapis.com", model, System.getenv("GEMINI_API_KEY"));
        byte[] source = Files.readAllBytes(Fixtures.PUBLIC_DIR.resolve("sample-hat.source.txt"));

        PatternParser.ParsedPattern parsed = new TextPatternParser(llm, mapper, model)
                .parse(new SourceFile("hat.txt", "text/plain", source), SourceType.TEXT_PDF, new ParseOptions(null));

        PatternIr expected = Fixtures.read(Fixtures.PUBLIC_DIR.resolve("sample-hat.ir.json"));
        assertThat(ends(parsed.ir())).isEqualTo(ends(expected));
        assertThat(parsed.ir().sourceLang()).isEqualTo("en");
        System.out.println("parsed in " + parsed.attempts() + " attempt(s)");
    }

    static java.util.List<Integer> ends(PatternIr ir) {
        return StitchSimulator.simulate(ir).steps().stream().map(PatternPlan.StepPlan::endSts).toList();
    }
}
