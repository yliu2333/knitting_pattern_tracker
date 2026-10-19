package com.knittracker.ir;

import java.util.List;

/**
 * Canonical, language-neutral pattern IR. This is the only thing the LLM parser may emit;
 * stitch counts that depend on arithmetic are left null and computed in Java.
 */
public record PatternIr(
        int irVersion,
        String title,
        Craft craft,
        SourceType sourceType,
        String sourceLang,
        Gauge gauge,
        List<SectionIr> sections) {

    public enum Craft { KNIT, CROCHET }

    public enum SourceType { TEXT_PDF, TEXT_IMAGE, JP_KNIT_CHART, CROCHET_CHART }

    /** Stitches and rows per {@code perCm} cm, on {@code needleMm}. */
    public record Gauge(double sts, double rows, double perCm, Double needleMm) {}
}
