package com.campusdeal.service;

import java.util.List;

/**
 * 幂等服务：Redis SETNX 快速去重（MySQL UNIQUE KEY 兜底）
 */
public interface IdempotentService {

    /**
     * 检查并标记消息为"处理中"
     *
     * @param dedupKey 去重键（格式：order:dedup:{userId}:{dealId}）
     * @return true = 首次处理（可以继续），false = 已处理过（应该跳过）
     */
    boolean tryMark(String dedupKey);

    /**
     * 批量检查并标记（一次 Redis pipeline 完成 N 个 SETNX，仅一次网络往返）
     *
     * @param dedupKeys 去重键列表
     * @return 与入参等长的布尔列表，true = 首次处理（可继续），false = 已处理过（应跳过）
     */
    List<Boolean> tryMarkBatch(List<String> dedupKeys);

    /**
     * 清除去重标记（用于补偿重试时重置）
     *
     * @param dedupKey 去重键
     */
    void clearMark(String dedupKey);
}
