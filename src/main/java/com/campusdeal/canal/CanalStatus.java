package com.campusdeal.canal;

import lombok.Builder;
import lombok.Data;

/**
 * Canal 客户端连接状态快照
 */
@Data
@Builder
public class CanalStatus {
    /** 是否运行中 */
    private boolean running;
    private String host;
    private int port;
    /** Number of reconnect attempts since the client was started. */
    private long reconnectCount;
    /** Last successfully handled batch size; zero means no event has arrived. */
    private long lastBatchSize;
    /** Epoch milliseconds of the last handled event, or zero when idle. */
    private long lastEventAt;
}
