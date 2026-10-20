package com.knittracker.service.parse;

import java.util.List;

/** The LLM output stayed invalid after all retries (or the LLM was unreachable). */
public class ParseFailedException extends RuntimeException {

    private final List<String> errors;

    public ParseFailedException(List<String> errors) {
        super("Pattern could not be parsed: " + String.join("; ", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> errors() {
        return errors;
    }
}
