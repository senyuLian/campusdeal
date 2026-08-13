package com.campusdeal.service;

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
     * 清除去重标记（用于补偿重试时重置）
     *
     * @param dedupKey 去重键
     */
    void clearMark(String dedupKey);
}
