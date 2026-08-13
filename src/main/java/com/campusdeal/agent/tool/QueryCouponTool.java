package com.campusdeal.agent.tool;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.campusdeal.agent.Tool;
import com.campusdeal.entity.Coupon;
import com.campusdeal.service.ICouponService;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 查询平台当前可领取的优惠券。
 */
@Component
public class QueryCouponTool {

    @Resource
    private ICouponService couponService;

    @Tool(name = "query_coupon", description = "查询平台当前上架的优惠券列表，返回 id、标题、面值、抵扣金额")
    public String queryCoupon() {
        List<Coupon> coupons = couponService.list(
                new QueryWrapper<Coupon>().eq("status", 1).last("limit 10"));
        if (coupons == null || coupons.isEmpty()) {
            return "{\"coupons\":[]}";
        }
        String items = coupons.stream()
                .map(c -> String.format("{\"id\":%d,\"title\":\"%s\",\"payValue\":%d,\"actualValue\":%d}",
                        c.getId(), c.getTitle(), c.getPayValue() == null ? 0 : c.getPayValue(),
                        c.getActualValue() == null ? 0 : c.getActualValue()))
                .collect(Collectors.joining(","));
        return "{\"coupons\":[" + items + "]}";
    }
}
