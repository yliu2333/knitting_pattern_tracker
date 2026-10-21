package com.knittracker.service.parse;

import com.knittracker.ir.PatternIr;
import com.knittracker.ir.PatternIr.Craft;
import com.knittracker.ir.PatternIr.SourceType;
import com.knittracker.service.technique.LearnedAlias;
import com.knittracker.service.technique.Technique;
import com.knittracker.service.technique.TechniqueLibrary;
import java.util.List;

/** Source file → canonical IR. v1: {@link TextPatternParser}; later: JP knit charts, crochet charts. */
public interface PatternParser {

    record SourceFile(String filename, String mimeType, byte[] data) {}

    /** @param size the size to extract from multi-size patterns ("M", "2", "Chest 96 cm"); null if one size */
    record ParseOptions(String size) {}

    /**
     * @param learned    techniques the pattern taught the system (new for this user)
     * @param aliases    new names for techniques already in the library
     * @param unsupported terms that could not be defined; they stay UNKNOWN (shown as raw text)
     */
    record ParsedPattern(PatternIr ir, int attempts, List<Technique> learned, List<LearnedAlias> aliases,
                         List<String> unsupported) {}

    boolean supports(SourceType type, Craft craft);

    /** Version string that goes into the parse cache key; bump it whenever prompt or model changes. */
    String version();

    /** @param library the uploader's techniques: built-in, promoted and their own learned ones */
    ParsedPattern parse(SourceFile file, SourceType type, ParseOptions options, TechniqueLibrary library);
}
