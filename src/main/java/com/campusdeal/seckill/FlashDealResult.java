package com.campusdeal.seckill;

/**
 * Lua 脚本返回值契约（T16 调整）
 *
 * <p>seckill.lua 在 Redis 服务端原子执行的返回码：</p>
 * <ul>
 *   <li>{@link #SUCCESS}         — 秒杀成功；码值 = 剩余库存（>= 0），服务端据此预置 L1 负缓存</li>
 *   <li>{@link #OUT_OF_STOCK}    — 库存不足（-1）</li>
 *   <li>{@link #DUPLICATE_ORDER} — 重复下单（-2，该用户已购买此活动）</li>
 * </ul>
 */
public enum FlashDealResult {
    SUCCESS(-1, "秒杀成功"),
    OUT_OF_STOCK(-1, "库存不足"),
    DUPLICATE_ORDER(-2, "不能重复下单");

    private final int code;
    private final String message;

    FlashDealResult(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    /**
     * 由 Lua 返回值判定结果：{@code code >= 0} 为成功（值 = 剩余库存），
     * {@code -1} 为无库存，{@code -2} 为重复下单。
     */
    public static FlashDealResult fromCode(long code) {
        if (code >= 0) return SUCCESS;
        for (FlashDealResult r : values()) {
            if (r.code == code) return r;
        }
        throw new IllegalArgumentException("Unknown result code: " + code);
    }
}
