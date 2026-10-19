package com.knittracker.service.parse;

import com.knittracker.service.llm.LlmClient;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Test double: returns queued responses and records every call. Only exercises the pipeline around the LLM. */
public class FakeLlm implements LlmClient {

    public record Call(String system, String user, List<Attachment> attachments) {}

    public final Deque<String> responses = new ArrayDeque<>();
    public final List<Call> calls = new ArrayList<>();

    public FakeLlm reply(String... rs) {
        responses.addAll(List.of(rs));
        return this;
    }

    @Override
    public String completeJson(String systemPrompt, String userPrompt, List<Attachment> attachments) {
        calls.add(new Call(systemPrompt, userPrompt, attachments));
        if (responses.isEmpty()) throw new IllegalStateException("no response queued");
        return responses.removeFirst();
    }
}
