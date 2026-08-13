package com.campusdeal.controller;


import com.campusdeal.dto.Result;
import com.campusdeal.entity.MerchantType;
import com.campusdeal.service.IMerchantTypeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;

import java.util.List;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/merchant-type")
public class MerchantTypeController {
    @Resource
    private IMerchantTypeService typeService;

    //查询一共有哪些店铺类型
    @GetMapping("list")
    public Result queryTypeList() {
        List<MerchantType> typeList = typeService.queryList();
        return Result.ok(typeList);
    }
}
