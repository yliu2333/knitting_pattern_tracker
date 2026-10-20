package com.knittracker.service.parse;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knittracker.ir.IrValidator;
import com.knittracker.ir.PatternIr;
import com.knittracker.ir.PatternIr.Craft;
import com.knittracker.ir.PatternIr.SourceType;
import com.knittracker.service.count.StitchSimulator;
import com.knittracker.service.llm.LlmClient;
import com.knittracker.service.llm.LlmClient.Attachment;
import com.knittracker.service.llm.LlmException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Written patterns in any language (PDF, photo or plain text) → IR via ONE logical LLM call.
 * Output is checked by Jackson, {@link IrValidator} and {@link StitchSimulator}; on failure the
 * errors are sent back for a corrected answer (each retry is a fresh, stateless call).
 */
@Component
public class TextPatternParser implements PatternParser {

    static final int MAX_ATTEMPTS = 3;
    static final Set<String> ATTACHABLE = Set.of("application/pdf", "image/png", "image/jpeg", "image/webp",
            "image/heic", "image/heif");

    private final LlmClient llm;
    private final ObjectMapper strict;
    private final String systemPrompt;
    private final String version;

    public TextPatternParser(LlmClient llm, ObjectMapper mapper, @Value("${app.llm.gemini.model}") String model) {
        this.llm = llm;
        this.strict = mapper.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        this.systemPrompt = resource("llm/parse-system.md") + "\n\n" + resource("llm/glossary.md");
        this.version = "text-v1/" + model;
    }

    @Override
    public boolean supports(SourceType type, Craft craft) {
        return craft == Craft.KNIT && (type == SourceType.TEXT_PDF || type == SourceType.TEXT_IMAGE);
    }

    @Override
    public String version() {
        return version;
    }

    @Override
    public ParsedPattern parse(SourceFile file, SourceType type, ParseOptions options) {
        List<Attachment> attachments = new ArrayList<>();
        String patternText = null;
        if (file.mimeType().startsWith("text/")) {
            patternText = new String(file.data(), StandardCharsets.UTF_8);
        } else if (ATTACHABLE.contains(file.mimeType())) {
            attachments.add(new Attachment(file.mimeType(), file.data()));
        } else {
            throw new IllegalArgumentException("unsupported file type: " + file.mimeType());
        }

        List<String> errors = List.of();
        String previous = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            String prompt = userPrompt(type, options, patternText, previous, errors);
            String raw;
            try {
                raw = llm.completeJson(systemPrompt, prompt, attachments);
            } catch (LlmException e) {
                throw new ParseFailedException(List.of(e.getMessage()));
            }
            previous = raw;
            PatternIr ir;
            try {
                ir = IrNormalizer.normalize(strict.readValue(stripFences(raw), PatternIr.class), type);
            } catch (IOException e) {
                errors = List.of("output is not valid JSON for the schema: " + firstLine(e.getMessage()));
                continue;
            }
            errors = IrValidator.validate(ir);
            if (errors.isEmpty()) {
                try {
                    StitchSimulator.simulate(ir);
                    return new ParsedPattern(ir, attempt);
                } catch (StitchSimulator.SimulationException e) {
                    errors = List.of("stitch simulation failed at " + e.getMessage());
                }
            }
        }
        throw new ParseFailedException(errors);
    }

    static String userPrompt(SourceType type, ParseOptions options, String patternText, String previous,
                             List<String> errors) {
        StringBuilder p = new StringBuilder();
        p.append("sourceType: ").append(type).append('\n');
        p.append("Size to extract: ")
                .append(options.size() == null || options.size().isBlank() ? "the pattern has one size" : options.size())
                .append('\n');
        if (patternText != null) {
            p.append("\n<pattern>\n").append(patternText).append("\n</pattern>\n");
        } else {
            p.append("\nThe pattern is in the attached file.\n");
        }
        if (previous != null) {
            p.append("\nYour previous output was rejected:\n");
            errors.forEach(e -> p.append("- ").append(e).append('\n'));
            p.append("\nPrevious output:\n").append(previous)
                    .append("\n\nReturn the complete corrected JSON. Fix only what the errors point to; still no arithmetic.\n");
        } else {
            p.append("\nReturn the JSON IR.\n");
        }
        return p.toString();
    }

    static String stripFences(String raw) {
        String s = raw.strip();
        if (s.startsWith("```")) {
            s = s.substring(s.indexOf('\n') + 1);
            int end = s.lastIndexOf("```");
            if (end >= 0) s = s.substring(0, end);
        }
        return s.strip();
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    private static String resource(String path) {
        try (InputStream in = TextPatternParser.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
