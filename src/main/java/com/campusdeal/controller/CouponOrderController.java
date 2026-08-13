package com.campusdeal.controller;

import com.campusdeal.dto.Result;
import com.campusdeal.service.IFlashDealService;
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

    @PostMapping("seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") Long dealId) {
        return flashDealService.executeFlashDeal(dealId);
    }
}
