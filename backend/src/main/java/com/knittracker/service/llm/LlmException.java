package com.knittracker.service.llm;

/** Transport-level failure. Messages never include prompt or pattern content. */
public class LlmException extends RuntimeException {
    public LlmException(String message) {
        super(message);
    }
}
