package com.knittracker.service.llm;

import java.util.List;

/**
 * Models to try, cheapest first. A parse only moves to the next model when the cheaper one could not produce
 * an IR that passes validation and simulation (or was unavailable), so most patterns cost Flash-Lite prices.
 */
public record LlmTiers(List<Tier> tiers) {

    public record Tier(String model, LlmClient client) {}

    public LlmTiers {
        if (tiers == null || tiers.isEmpty()) throw new IllegalArgumentException("at least one model is required");
        tiers = List.copyOf(tiers);
    }

    public static LlmTiers single(String model, LlmClient client) {
        return new LlmTiers(List.of(new Tier(model, client)));
    }
}
