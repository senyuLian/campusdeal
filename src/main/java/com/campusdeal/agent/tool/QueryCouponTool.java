package com.campusdeal.agent.tool;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.campusdeal.agent.Tool;
import com.campusdeal.entity.Coupon;
import com.campusdeal.service.ICouponService;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.List;

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
        // T11：手拼 JSON 在标题含引号时非法，改用 JSON 对象序列化
        cn.hutool.json.JSONArray arr = cn.hutool.json.JSONUtil.createArray();
        for (Coupon c : coupons) {
            arr.add(cn.hutool.json.JSONUtil.createObj()
                    .set("id", c.getId())
                    .set("title", c.getTitle())
                    .set("payValue", c.getPayValue() == null ? 0 : c.getPayValue())
                    .set("actualValue", c.getActualValue() == null ? 0 : c.getActualValue()));
        }
        return cn.hutool.json.JSONUtil.createObj().set("coupons", arr).toString();
    }
}
