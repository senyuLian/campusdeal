package com.campusdeal.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 会话结束事件（done），携带最终回复与统计信息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentResponse {

    private String sessionId;
    private String answer;
    private int totalTokens;
    private int toolCallCount;
    private long elapsedTimeMs;
}
