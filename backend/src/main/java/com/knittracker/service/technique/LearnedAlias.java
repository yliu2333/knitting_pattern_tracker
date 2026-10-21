package com.knittracker.service.technique;

/** A name the user's pattern used for a technique (their own learned one or a global one). */
public record LearnedAlias(String alias, String lang, String code) {}
