package com.knittracker.service.parse;

import java.util.List;

/** The LLM output stayed invalid after all retries (or the LLM was unreachable). */
public class ParseFailedException extends RuntimeException {

    private final List<String> errors;
    private final String lastOutput;

    /** @param lastOutput the model's last raw answer, for offline diagnosis only: never log or return it */
    public ParseFailedException(List<String> errors, String lastOutput) {
        super("Pattern could not be parsed: " + String.join("; ", errors));
        this.errors = List.copyOf(errors);
        this.lastOutput = lastOutput;
    }

    public ParseFailedException(List<String> errors) {
        this(errors, null);
    }

    public String lastOutput() {
        return lastOutput;
    }

    public List<String> errors() {
        return errors;
    }
}
