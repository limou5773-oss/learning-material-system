package com.learningmaterial.system.integration.model;

public interface ModelClient {
    String providerKey();

    Result generate(String model, String systemPrompt, String userPrompt);

    record Result(
            String content, String model, String requestId, Long inputTokens, Long outputTokens) {}
}
