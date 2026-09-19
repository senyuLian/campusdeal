package com.campusdeal.controller;

import com.campusdeal.dto.Result;
import com.campusdeal.service.IFlashDealService;
import com.campusdeal.service.FlashOrderIntentService;
import com.campusdeal.security.AuthorizationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;

@RestController
@RequestMapping("/coupon-order")
public class CouponOrderController {

    @Resource
    private IFlashDealService flashDealService;
    @Resource
    private FlashOrderIntentService flashOrderIntentService;
    @Resource
    private AuthorizationService authorizationService;

    @PostMapping("seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") Long dealId) {
        return flashDealService.executeFlashDeal(dealId);
    }

    @GetMapping("/{orderId}")
    public Result status(@PathVariable("orderId") Long orderId) {
        return flashOrderIntentService.status(orderId, authorizationService.requireAuthenticated().getId());
    }
}
