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
}
