package com.campusdeal.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 一条对话消息记录（可序列化，用于会话持久化）。
 *
 * <p>role 取值：user / assistant / system / tool。
 * 工具消息额外携带 toolCallId / toolName，便于重建 ToolExecutionResultMessage。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    private String role;
    private String content;
    private String toolCallId;
    private String toolName;
    private Long timestamp;
}
