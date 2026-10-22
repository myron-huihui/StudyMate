package com.studymate.ai.agent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 工作流节点事件回调，用于 SSE 推送（默认空实现） */
public interface PlanAgentListener {

    PlanAgentListener NOOP = new PlanAgentListener() {
    };

    /** 回调实例不可序列化，状态里只放 ID，运行时按 ID 取回真实实例 */
    ConcurrentHashMap<String, PlanAgentListener> REGISTRY = new ConcurrentHashMap<>();

    default void onStage(String stage, String message) {
    }

    default void onReview(Map<String, Object> review) {
    }

    static String register(PlanAgentListener listener) {
        if (listener == null) {
            return null;
        }
        String id = UUID.randomUUID().toString();
        REGISTRY.put(id, listener);
        return id;
    }

    static PlanAgentListener of(String listenerId) {
        if (listenerId == null) {
            return NOOP;
        }
        return REGISTRY.getOrDefault(listenerId, NOOP);
    }

    static void unregister(String listenerId) {
        if (listenerId != null) {
            REGISTRY.remove(listenerId);
        }
    }
}
