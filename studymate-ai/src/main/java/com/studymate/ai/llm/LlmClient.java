package com.studymate.ai.llm;

public interface LlmClient {

    LlmFramework framework();

    LlmResult chat(LlmModelSpec spec, LlmRequest request);

    float[] embed(LlmModelSpec spec, String text);
}
