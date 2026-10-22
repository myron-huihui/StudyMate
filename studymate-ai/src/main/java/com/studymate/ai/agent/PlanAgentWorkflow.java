package com.studymate.ai.agent;

import com.studymate.ai.config.AiProperties;
import com.studymate.ai.service.GoalUnderstandingService;
import com.studymate.ai.service.PlanGenerationService;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.NodeAction;
import org.bsc.langgraph4j.state.AgentStateFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.bsc.langgraph4j.GraphDefinition.END;
import static org.bsc.langgraph4j.GraphDefinition.START;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 计划生成 Agent 工作流（LangGraph4j）：
 * understand → generate → review → (rewrite → generate)* → finalize
 * 审查不通过走循环边带 critique 重写，超过最大次数仍定稿并标记 needsManual。
 */
@Slf4j
@Component
public class PlanAgentWorkflow {

    private static final String NODE_UNDERSTAND = "understand";
    private static final String NODE_GENERATE = "generate";
    private static final String NODE_REVIEW = "review";
    private static final String NODE_REWRITE = "rewrite";
    private static final String NODE_FINALIZE = "finalize";

    private final GoalUnderstandingService goalUnderstandingService;
    private final PlanGenerationService planGenerationService;
    private final QualityReviewService qualityReviewService;
    private final int maxRewrites;
    private final CompiledGraph<PlanAgentState> graph;

    public PlanAgentWorkflow(GoalUnderstandingService goalUnderstandingService,
                             PlanGenerationService planGenerationService,
                             QualityReviewService qualityReviewService,
                             AiProperties aiProperties) {
        this.goalUnderstandingService = goalUnderstandingService;
        this.planGenerationService = planGenerationService;
        this.qualityReviewService = qualityReviewService;
        this.maxRewrites = aiProperties.getAgent().getMaxRewrites();
        this.graph = buildGraph();
    }

    public PlanWorkflowResult execute(String goal, double dailyHours, String currentLevel,
                                      Integer totalDays, String memoryContext, PlanAgentListener listener) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put(PlanAgentState.GOAL, goal);
        input.put(PlanAgentState.DAILY_HOURS, dailyHours);
        input.put(PlanAgentState.CURRENT_LEVEL, currentLevel == null ? "" : currentLevel);
        input.put(PlanAgentState.TOTAL_DAYS, totalDays);
        input.put(PlanAgentState.MEMORY_CONTEXT, memoryContext == null ? "" : memoryContext);
        input.put(PlanAgentState.REWRITE_COUNT, 0);
        String listenerId = PlanAgentListener.register(listener);
        input.put(PlanAgentState.LISTENER_ID, listenerId);

        PlanAgentState state;
        try {
            state = graph.invoke(GraphInput.args(input), RunnableConfig.empty())
                    .orElseThrow(() -> new IllegalStateException("计划工作流未产生输出"));
        } finally {
            PlanAgentListener.unregister(listenerId);
        }
        ReviewResult review = state.review();
        return PlanWorkflowResult.builder()
                .understanding(state.understanding())
                .planJson(state.planJson())
                .review(review)
                .rewriteCount(state.rewriteCount())
                .passed(state.passed())
                .needsManual(state.needsManual())
                .build();
    }

    private CompiledGraph<PlanAgentState> buildGraph() {
        AgentStateFactory<PlanAgentState> factory = PlanAgentState::new;
        try {
            return new StateGraph<>(factory)
                    .addNode(NODE_UNDERSTAND, node_async(understand()))
                    .addNode(NODE_GENERATE, node_async(generate()))
                    .addNode(NODE_REVIEW, node_async(review()))
                    .addNode(NODE_REWRITE, node_async(rewrite()))
                    .addNode(NODE_FINALIZE, node_async(finalizeNode()))
                    .addEdge(START, NODE_UNDERSTAND)
                    .addEdge(NODE_UNDERSTAND, NODE_GENERATE)
                    .addEdge(NODE_GENERATE, NODE_REVIEW)
                    .addConditionalEdges(NODE_REVIEW, AsyncEdgeAction.edge_async(this::routeAfterReview),
                            Map.of("rewrite", NODE_REWRITE, "finalize", NODE_FINALIZE))
                    .addEdge(NODE_REWRITE, NODE_GENERATE)
                    .addEdge(NODE_FINALIZE, END)
                    .compile();
        } catch (GraphStateException e) {
            throw new IllegalStateException("计划工作流图构建失败", e);
        }
    }

    private NodeAction<PlanAgentState> understand() {
        return state -> {
            state.listener().onStage(NODE_UNDERSTAND, "正在理解学习目标");
            String understanding = goalUnderstandingService.understand(
                    state.goal(), state.dailyHours(), state.currentLevel(), state.totalDays());
            return Map.of(PlanAgentState.UNDERSTANDING, understanding);
        };
    }

    private NodeAction<PlanAgentState> generate() {
        return state -> {
            int round = state.rewriteCount();
            state.listener().onStage(NODE_GENERATE,
                    round == 0 ? "正在生成学习计划" : "正在按审查意见重写计划（第 " + round + " 次）");
            String planJson = planGenerationService.generate(
                    state.goal(), state.dailyHours(), state.currentLevel(), state.totalDays(),
                    state.understanding(), state.memoryContext(), state.critique());
            return Map.of(PlanAgentState.PLAN_JSON, planJson);
        };
    }

    private NodeAction<PlanAgentState> review() {
        return state -> {
            state.listener().onStage(NODE_REVIEW, "正在做四维质量审查");
            ReviewResult result = qualityReviewService.review(
                    state.planJson(), state.goal(), state.dailyHours());
            state.listener().onReview(toPayload(result));
            log.debug("Plan review round {}: passed={} avg={} degraded={}",
                    state.rewriteCount(), result.isPassed(), result.getAverage(), result.isDegraded());
            return Map.of(PlanAgentState.REVIEW, result, PlanAgentState.PASSED, result.isPassed());
        };
    }

    private NodeAction<PlanAgentState> rewrite() {
        return state -> {
            ReviewResult review = state.review();
            String critique = review == null ? "" : review.getCritique();
            state.listener().onStage(NODE_REWRITE, "审查未通过，准备重写");
            Map<String, Object> update = new LinkedHashMap<>();
            update.put(PlanAgentState.REWRITE_COUNT, state.rewriteCount() + 1);
            update.put(PlanAgentState.CRITIQUE, critique);
            update.put(PlanAgentState.PASSED, false);
            return update;
        };
    }

    private NodeAction<PlanAgentState> finalizeNode() {
        return state -> {
            boolean passed = state.passed();
            ReviewResult review = state.review();
            boolean needsManual = !passed || (review != null && review.isDegraded());
            state.listener().onStage(NODE_FINALIZE,
                    needsManual ? "已输出计划，建议人工复核" : "计划通过质量审查");
            Map<String, Object> update = new LinkedHashMap<>();
            update.put(PlanAgentState.PASSED, passed);
            update.put(PlanAgentState.NEEDS_MANUAL, needsManual);
            return update;
        };
    }

    private String routeAfterReview(PlanAgentState state) {
        ReviewResult review = state.review();
        if (review == null || review.isPassed()) {
            return "finalize";
        }
        return state.rewriteCount() < maxRewrites ? "rewrite" : "finalize";
    }

    private Map<String, Object> toPayload(ReviewResult review) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("passed", review.isPassed());
        payload.put("average", review.getAverage());
        payload.put("scores", review.getScores());
        payload.put("degraded", review.isDegraded());
        payload.put("critique", review.getCritique());
        return payload;
    }
}
