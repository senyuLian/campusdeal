package com.campusdeal.agent.tool;

import com.campusdeal.agent.Tool;
import com.campusdeal.entity.CouponOrder;
import com.campusdeal.service.ICouponOrderService;
import com.campusdeal.utils.UserHolder;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

/**
 * 为当前用户申请订单退款（敏感操作：requireConfirmation = true）。
 */
@Component
public class ApplyRefundTool {

    @Resource
    private ICouponOrderService couponOrderService;

    @Tool(name = "apply_refund", description = "为当前用户的订单申请退款；仅限本人订单，且订单状态为未支付或已支付",
            requireConfirmation = true)
    public String applyRefund(Long orderId) {
        Long userId = UserHolder.getUser() == null ? null : UserHolder.getUser().getId();
        if (userId == null) {
            return "{\"error\":\"用户未登录\"}";
        }
        CouponOrder order = couponOrderService.getById(orderId);
        if (order == null) {
            return "{\"error\":\"订单不存在\"}";
        }
        if (!userId.equals(order.getUserId())) {
            return "{\"error\":\"无权操作他人订单\"}";
        }
        boolean ok = couponOrderService.update()
                .eq("id", orderId)
                .in("status", 1, 2)
                .set("status", 5) // 5=退款中
                .setSql("refund_time = now()")
                .update();
        return ok ? "{\"success\":true,\"message\":\"退款申请已提交\"}"
                  : "{\"error\":\"订单状态不允许退款\"}";
    }
}
