package com.campusdeal.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SSE 推送到前端的事件载荷。
 *
 * <p>type 取值：thinking / tool_call / tool_result / confirm / chunk / done / error。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentEvent {

    private String type;
    private String content;
    private Long timestamp;
}
