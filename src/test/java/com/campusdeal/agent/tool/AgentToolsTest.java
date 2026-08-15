package com.campusdeal.agent.tool;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.update.UpdateChainWrapper;
import com.campusdeal.dto.UserDTO;
import com.campusdeal.entity.Coupon;
import com.campusdeal.entity.CouponOrder;
import com.campusdeal.entity.Merchant;
import com.campusdeal.service.ICouponOrderService;
import com.campusdeal.service.ICouponService;
import com.campusdeal.service.IMerchantService;
import com.campusdeal.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T10：Agent 工具单元测试（TOOL-01..11）。
 *
 * <p>Mock 各 Service + UserHolder 提供当前用户，直接调用工具方法，断言 JSON 结果契约。</p>
 */
@ExtendWith(MockitoExtension.class)
class AgentToolsTest {

    @Mock private ICouponOrderService couponOrderService;
    @Mock private IMerchantService merchantService;
    @Mock private ICouponService couponService;

    @InjectMocks private QueryOrderTool queryOrderTool;
    @InjectMocks private SearchMerchantTool searchMerchantTool;
    @InjectMocks private QueryCouponTool queryCouponTool;
    @InjectMocks private ApplyRefundTool applyRefundTool;

    @BeforeEach
    void setUp() {
        UserDTO u = new UserDTO();
        u.setId(1001L);
        UserHolder.saveUser(u);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    // ==================== QueryOrderTool ====================

    @Test
    @DisplayName("TOOL-01 query_order 正常：返回订单列表 JSON")
    void queryOrderReturnsOrders() {
        CouponOrder order = new CouponOrder();
        order.setId(1L);
        order.setVoucherId(10L);
        order.setStatus(1);
        when(couponOrderService.list(any(QueryWrapper.class))).thenReturn(List.of(order));

        String result = queryOrderTool.queryOrder();

        assertThat(result).contains("\"id\":1").contains("\"voucherId\":10").contains("\"status\":1");
        assertThat(JSONUtil.parseObj(result).getJSONArray("orders")).hasSize(1);
    }

    @Test
    @DisplayName("TOOL-02 query_order 空：返回空数组")
    void queryOrderEmpty() {
        when(couponOrderService.list(any(QueryWrapper.class))).thenReturn(List.of());

        String result = queryOrderTool.queryOrder();

        assertThat(result).isEqualTo("{\"orders\":[]}");
    }

    @Test
    @DisplayName("TOOL-03 query_order 未登录：error 用户未登录")
    void queryOrderNotLoggedIn() {
        UserHolder.removeUser();

        String result = queryOrderTool.queryOrder();

        assertThat(result).contains("用户未登录");
    }

    // ==================== SearchMerchantTool ====================

    @Test
    @DisplayName("TOOL-04 search_merchant 命中：返回商户列表")
    void searchMerchantHit() {
        Merchant m = new Merchant();
        m.setId(7L);
        m.setName("校园咖啡");
        m.setAvgPrice(20L);
        when(merchantService.list(any(QueryWrapper.class))).thenReturn(List.of(m));

        String result = searchMerchantTool.searchMerchant("咖啡");

        assertThat(result).contains("\"id\":7").contains("\"name\":\"校园咖啡\"");
        assertThat(JSONUtil.parseObj(result).getJSONArray("merchants")).hasSize(1);
        verify(merchantService).list(any(QueryWrapper.class));
    }

    @Test
    @DisplayName("TOOL-05 search_merchant 空 keyword：error")
    void searchMerchantBlankKeyword() {
        String result = searchMerchantTool.searchMerchant("   ");

        assertThat(result).contains("keyword 不能为空");
    }

    @Test
    @DisplayName("TOOL-11 search_merchant 商户名含引号/反斜杠 → 仍为合法 JSON（T11）")
    void searchMerchantEscapesQuotes() {
        Merchant m = new Merchant();
        m.setId(7L);
        m.setName("Coffee \"Latte\" \\ Cafe");
        m.setAvgPrice(20L);
        when(merchantService.list(any(QueryWrapper.class))).thenReturn(List.of(m));

        String result = searchMerchantTool.searchMerchant("Coffee");

        cn.hutool.json.JSONObject parsed = JSONUtil.parseObj(result);
        assertThat(parsed.getJSONArray("merchants").getJSONObject(0).getStr("name"))
                .isEqualTo("Coffee \"Latte\" \\ Cafe");
    }

    // ==================== QueryCouponTool ====================

    @Test
    @DisplayName("TOOL-06 query_coupon：返回券列表")
    void queryCouponReturnsList() {
        Coupon c = new Coupon();
        c.setId(3L);
        c.setTitle("满20减5");
        c.setPayValue(5L);
        c.setActualValue(20L);
        when(couponService.list(any(QueryWrapper.class))).thenReturn(List.of(c));

        String result = queryCouponTool.queryCoupon();

        assertThat(result).contains("\"title\":\"满20减5\"");
        assertThat(JSONUtil.parseObj(result).getJSONArray("coupons")).hasSize(1);
    }

    @Test
    @DisplayName("TOOL-11 query_coupon 标题含引号 → 仍为合法 JSON（T11）")
    void queryCouponEscapesQuotes() {
        Coupon c = new Coupon();
        c.setId(3L);
        c.setTitle("充值\"立减\"券");
        c.setPayValue(5L);
        c.setActualValue(20L);
        when(couponService.list(any(QueryWrapper.class))).thenReturn(List.of(c));

        String result = queryCouponTool.queryCoupon();

        cn.hutool.json.JSONObject parsed = JSONUtil.parseObj(result);
        assertThat(parsed.getJSONArray("coupons").getJSONObject(0).getStr("title"))
                .isEqualTo("充值\"立减\"券");
    }

    // ==================== ApplyRefundTool ====================

    private UpdateChainWrapper<CouponOrder> stubRefundUpdate(boolean ok) {
        UpdateChainWrapper<CouponOrder> wrapper = mock(UpdateChainWrapper.class);
        when(couponOrderService.update()).thenReturn(wrapper);
        // 链式调用逐一返回自身（RETURNS_SELF 对泛型 Children 返回类型不生效，需显式桩）
        when(wrapper.eq(any(), any())).thenReturn(wrapper);
        when(wrapper.in(any(), any(Object[].class))).thenReturn(wrapper);  // in("status", 1, 2)
        when(wrapper.set(any(), any())).thenReturn(wrapper);
        when(wrapper.setSql(any())).thenReturn(wrapper);
        when(wrapper.update()).thenReturn(ok);
        return wrapper;
    }

    private CouponOrder orderOf(Long id, Long userId) {
        CouponOrder o = new CouponOrder();
        o.setId(id);
        o.setUserId(userId);
        o.setVoucherId(10L);
        o.setStatus(1);
        return o;
    }

    @Test
    @DisplayName("TOOL-07 apply_refund 正常：本人订单 status=1 → success")
    void applyRefundSuccess() {
        when(couponOrderService.getById(99L)).thenReturn(orderOf(99L, 1001L));
        stubRefundUpdate(true);

        String result = applyRefundTool.applyRefund(99L);

        assertThat(result).contains("\"success\":true");
        verify(couponOrderService).update();
    }

    @Test
    @DisplayName("TOOL-08 apply_refund 越权：他人订单 → error")
    void applyRefundNotOwner() {
        when(couponOrderService.getById(99L)).thenReturn(orderOf(99L, 999L));

        String result = applyRefundTool.applyRefund(99L);

        assertThat(result).contains("无权操作他人订单");
    }

    @Test
    @DisplayName("TOOL-09 apply_refund 订单不存在 → error")
    void applyRefundNotFound() {
        when(couponOrderService.getById(99L)).thenReturn(null);

        String result = applyRefundTool.applyRefund(99L);

        assertThat(result).contains("订单不存在");
    }

    @Test
    @DisplayName("TOOL-10 apply_refund 状态不允许（update 返回 false）→ error")
    void applyRefundBadStatus() {
        when(couponOrderService.getById(99L)).thenReturn(orderOf(99L, 1001L));
        stubRefundUpdate(false);

        String result = applyRefundTool.applyRefund(99L);

        assertThat(result).contains("订单状态不允许退款");
    }
}
