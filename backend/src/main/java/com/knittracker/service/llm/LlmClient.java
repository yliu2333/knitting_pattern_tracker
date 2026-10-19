package com.knittracker.service.llm;

import java.util.List;

/**
 * Stateless LLM call: each request carries only the context passed in. No conversation memory,
 * no shared buffers (isolation rule 5). Implementations must not log prompts or attachments.
 */
public interface LlmClient {

    /** A file sent alongside the prompt (PDF page images, photos, the PDF itself). */
    record Attachment(String mimeType, byte[] data) {}

    /** Returns the model's raw text, expected to be a single JSON document. */
    String completeJson(String systemPrompt, String userPrompt, List<Attachment> attachments);
}
