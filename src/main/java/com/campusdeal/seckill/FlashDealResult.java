package com.campusdeal.seckill;

/**
 * Lua 脚本返回值枚举
 *
 * <p>seckill.lua 在 Redis 服务端原子执行的返回码：</p>
 * <ul>
 *   <li>{@link #SUCCESS}         — 秒杀成功</li>
 *   <li>{@link #OUT_OF_STOCK}    — 库存不足</li>
 *   <li>{@link #DUPLICATE_ORDER} — 重复下单（该用户已购买此活动）</li>
 * </ul>
 */
public enum FlashDealResult {
    SUCCESS(0, "秒杀成功"),
    OUT_OF_STOCK(1, "库存不足"),
    DUPLICATE_ORDER(2, "不能重复下单");

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

    public static FlashDealResult fromCode(long code) {
        for (FlashDealResult r : values()) {
            if (r.code == code) return r;
        }
        throw new IllegalArgumentException("Unknown result code: " + code);
    }
}
