package com.campusdeal.agent.tool;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.campusdeal.agent.Tool;
import com.campusdeal.entity.CouponOrder;
import com.campusdeal.service.ICouponOrderService;
import com.campusdeal.utils.UserHolder;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 查询当前用户订单。
 */
@Component
public class QueryOrderTool {

    @Resource
    private ICouponOrderService couponOrderService;

    @Tool(name = "query_order", description = "查询当前登录用户的优惠券订单列表，返回订单 id、voucherId 与状态")
    public String queryOrder() {
        Long userId = UserHolder.getUser() == null ? null : UserHolder.getUser().getId();
        if (userId == null) {
            return "{\"error\":\"用户未登录\"}";
        }
        List<CouponOrder> orders = couponOrderService.list(
                new QueryWrapper<CouponOrder>().eq("user_id", userId));
        if (orders == null || orders.isEmpty()) {
            return "{\"orders\":[]}";
        }
        String items = orders.stream()
                .map(o -> String.format("{\"id\":%d,\"voucherId\":%d,\"status\":%d}",
                        o.getId(), o.getVoucherId(), o.getStatus()))
                .collect(Collectors.joining(","));
        return "{\"orders\":[" + items + "]}";
    }
}
