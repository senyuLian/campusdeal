package com.campusdeal.security;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 压缩后的对话摘要（Prime Agent 风格结构化四段式）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompactedSummary {

    /** 用户的目标 */
    private String goal;

    /** 当前进度（已完成/进行中的步骤） */
    private String progress;

    /** 关键信息（已获取的数据/结果） */
    private String keyInfo;

    /** 下一步建议 */
    private String nextSteps;
}
