package com.campusdeal.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 一次 Agent 会话（对应 Redis Hash 中持久化的结构）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentSession {

    private String sessionId;
    private Long userId;
    /** Monotonically increasing save version used for optimistic concurrency checks. */
    private long version;
    private List<MessageRecord> messages;
    private String summary;
}
