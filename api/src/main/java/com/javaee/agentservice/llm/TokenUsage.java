package com.javaee.agentservice.llm;

/**
 * What one or more model turns consumed.
 *
 * <p>Lives with the client rather than with the caller that budgets against it:
 * the counts are what the provider reported, and a second caller counting the
 * same turns would have to restate them.</p>
 *
 * <p>Cost is deliberately absent: it is a function of these counts and the
 * model's price, and baking it in would freeze a price that changes.</p>
 *
 * <p>{@code cachedPromptTokens} is a subset of {@code promptTokens}, not an
 * addition to it: a cache hit is input the provider still read, only cheaper.
 * It is kept apart because the ratio between the two is the only way to tell
 * whether a change to how the chain is assembled kept the prefix intact or
 * rewrote it — which is a property of the assembly, invisible in the total.</p>
 */
public record TokenUsage(long promptTokens,
                         long cachedPromptTokens,
                         long completionTokens,
                         int modelCalls) {

    /** For providers that report no cache, and callers that do not care. */
    public TokenUsage(long promptTokens, long completionTokens, int modelCalls) {
        this(promptTokens, 0L, completionTokens, modelCalls);
    }

    public static TokenUsage zero() {
        return new TokenUsage(0L, 0L, 0L, 0);
    }

    public TokenUsage plus(TokenUsage other) {
        return new TokenUsage(
                promptTokens + other.promptTokens(),
                cachedPromptTokens + other.cachedPromptTokens(),
                completionTokens + other.completionTokens(),
                modelCalls + other.modelCalls());
    }
}
