package com.knittracker.service.parse;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.knittracker.ir.Fixtures;
import com.knittracker.ir.PatternIr.SourceType;
import com.knittracker.service.count.ExpectedCounts;
import com.knittracker.service.llm.GeminiClient;
import com.knittracker.service.parse.PatternParser.ParseOptions;
import com.knittracker.service.parse.PatternParser.SourceFile;
import com.knittracker.service.technique.TechniqueLibrary;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Real Gemini parse of each purchased pattern in the private repo (GEMINI_LIVE=1), graded by the counts the
 * pattern states (expected/*.expected.json). Every raw answer is saved to the private repo's parsed/ folder
 * so prompt changes can be compared later. Nothing is written to the public repo.
 */
@EnabledIfEnvironmentVariable(named = "GEMINI_LIVE", matches = "1|true")
class LivePrivateSourcesTest {

    static final Path PRIVATE = Fixtures.PUBLIC_DIR.resolve("private");

    static Stream<ExpectedCounts> sources() {
        return ExpectedCounts.all().stream().filter(e -> e.source() != null && Files.exists(PRIVATE.resolve(e.source())));
    }

    @ParameterizedTest
    @MethodSource("sources")
    void parsedPatternReachesEveryStatedCount(ExpectedCounts expected) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String model = System.getenv().getOrDefault("GEMINI_MODEL", "gemini-3.8-flash");
        var llm = new GeminiClient(HttpClient.newHttpClient(), mapper, "https://generativelanguage.googleapis.com",
                model, System.getenv().getOrDefault("GEMINI_API_KEY", ""),
                System.getenv().getOrDefault("GEMINI_THINKING_LEVEL", ""));
        TextPatternParser parser = new TextPatternParser(llm, mapper, model);
        Path source = PRIVATE.resolve(expected.source());
        String mime = source.toString().endsWith(".pdf") ? "application/pdf" : "text/plain";
        Path out = PRIVATE.resolve("parsed").resolve(expected.name() + "." + parser.version().replace('/', '_')
                + "." + LocalDate.now() + ".json");
        Files.createDirectories(out.getParent());

        List<String> problems;
        try {
            PatternParser.ParsedPattern parsed = parser.parse(
                    new SourceFile(source.getFileName().toString(), mime, Files.readAllBytes(source)),
                    SourceType.TEXT_PDF, new ParseOptions(null), TechniqueLibrary.builtin());
            Files.writeString(out, mapper.enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(parsed.ir()));
            System.out.println(expected.name() + ": parsed in " + parsed.attempts() + " attempt(s) -> " + out);
            problems = expected.check(parsed.ir());
        } catch (ParseFailedException e) {
            if (e.lastOutput() != null) Files.writeString(out.resolveSibling(out.getFileName() + ".failed"), e.lastOutput());
            problems = e.errors();
        }
        assertThat(problems).as(expected.name()).isEmpty();
    }
}
