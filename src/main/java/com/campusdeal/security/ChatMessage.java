package com.campusdeal.security;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 聊天消息轻量 DTO（用于 {@link CompactionService#injectSummary} 注入摘要）。
 *
 * <p>role 取值：system / user / assistant。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {

    private String role;
    private String content;
    private Long timestamp;
}
