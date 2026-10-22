package com.studymate.ai.agent;

import org.bsc.langgraph4j.state.AgentState;

import java.util.Map;
import java.util.Optional;

/** 计划生成工作流状态 */
public class PlanAgentState extends AgentState {

    public static final String GOAL = "goal";
    public static final String DAILY_HOURS = "dailyHours";
    public static final String CURRENT_LEVEL = "currentLevel";
    public static final String TOTAL_DAYS = "totalDays";
    public static final String MEMORY_CONTEXT = "memoryContext";
    public static final String UNDERSTANDING = "understanding";
    public static final String PLAN_JSON = "planJson";
    public static final String REVIEW = "review";
    public static final String CRITIQUE = "critique";
    public static final String REWRITE_COUNT = "rewriteCount";
    public static final String PASSED = "passed";
    public static final String NEEDS_MANUAL = "needsManual";
    public static final String LISTENER_ID = "listenerId";

    public PlanAgentState(Map<String, Object> initData) {
        super(initData);
    }

    public String goal() {
        return this.<String>value(GOAL).orElse("");
    }

    public double dailyHours() {
        return this.<Number>value(DAILY_HOURS).map(Number::doubleValue).orElse(1.0);
    }

    public String currentLevel() {
        return this.<String>value(CURRENT_LEVEL).orElse("");
    }

    public Integer totalDays() {
        return this.<Number>value(TOTAL_DAYS).map(Number::intValue).orElse(null);
    }

    public String memoryContext() {
        return this.<String>value(MEMORY_CONTEXT).orElse("");
    }

    public String understanding() {
        return this.<String>value(UNDERSTANDING).orElse("");
    }

    public String planJson() {
        return this.<String>value(PLAN_JSON).orElse("");
    }

    public ReviewResult review() {
        return this.<ReviewResult>value(REVIEW).orElse(null);
    }

    public String critique() {
        return this.<String>value(CRITIQUE).orElse("");
    }

    public int rewriteCount() {
        return this.<Number>value(REWRITE_COUNT).map(Number::intValue).orElse(0);
    }

    public boolean passed() {
        return this.<Boolean>value(PASSED).orElse(false);
    }

    public boolean needsManual() {
        return this.<Boolean>value(NEEDS_MANUAL).orElse(false);
    }

    public PlanAgentListener listener() {
        return PlanAgentListener.of(this.<String>value(LISTENER_ID).orElse(null));
    }

    public Optional<ReviewResult> reviewOptional() {
        return Optional.ofNullable(review());
    }
}
