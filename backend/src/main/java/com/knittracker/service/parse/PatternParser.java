package com.knittracker.service.parse;

import com.knittracker.ir.PatternIr;
import com.knittracker.ir.PatternIr.Craft;
import com.knittracker.ir.PatternIr.SourceType;

/** Source file → canonical IR. v1: {@link TextPatternParser}; later: JP knit charts, crochet charts. */
public interface PatternParser {

    record SourceFile(String filename, String mimeType, byte[] data) {}

    /** @param size the size to extract from multi-size patterns ("M", "2", "Chest 96 cm"); null if one size */
    record ParseOptions(String size) {}

    record ParsedPattern(PatternIr ir, int attempts) {}

    boolean supports(SourceType type, Craft craft);

    /** Version string that goes into the parse cache key; bump it whenever prompt or model changes. */
    String version();

    ParsedPattern parse(SourceFile file, SourceType type, ParseOptions options);
}
