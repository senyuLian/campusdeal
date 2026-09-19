package com.campusdeal.controller;


import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campusdeal.dto.Result;
import com.campusdeal.dto.MerchantWriteRequest;
import com.campusdeal.entity.Merchant;
import com.campusdeal.entity.MerchantType;
import com.campusdeal.service.IMerchantService;
import com.campusdeal.service.IMerchantTypeService;
import com.campusdeal.service.impl.MerchantTypeServiceImpl;
import com.campusdeal.utils.SystemConstants;
import com.campusdeal.utils.UserHolder;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import org.springframework.validation.annotation.Validated;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;

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
@RequestMapping("/merchant")
@Validated
public class MerchantController {

    @Resource
    public IMerchantService shopService;


    /**
     * 根据id查询商铺信息
     * @param id 商铺id
     * @return 商铺详情数据
     */
    @GetMapping("/{id}")
    public Result queryShopById(@PathVariable("id") Long id) {
//        return Result.ok(shopService.getById(id));
        return shopService.queryById(id);
    }


    /**
     * 新增商铺信息
     * @param merchant 商铺数据
     * @return 商铺id
     */
    @PostMapping
    public Result saveShop(@Valid @RequestBody MerchantWriteRequest request, HttpServletResponse response) {
        // P0-4 修复：写接口回归登录保护（/merchant/** 已排除拦截器，需在此校验）
        if (UserHolder.getUser() == null) {
            response.setStatus(401);
            return Result.fail("UNAUTHORIZED", "请先登录");
        }
        // 写入数据库
        Merchant merchant = toEntity(request);
        shopService.save(merchant);
        // 返回店铺id
        return Result.ok(merchant.getId());
    }

    /**
     * 更新商铺信息
     * @param merchant 商铺数据
     * @return 无
     */
    @PutMapping
    public Result updateShop(@Valid @RequestBody MerchantWriteRequest request, HttpServletResponse response) {
        // P0-4 修复：写接口回归登录保护
        if (UserHolder.getUser() == null) {
            response.setStatus(401);
            return Result.fail("UNAUTHORIZED", "请先登录");
        }
        // 写入数据库

        return shopService.updateShop(toEntity(request));
    }

    /**
     * 根据商铺类型分页查询商铺信息
     * @param typeId 商铺类型
     * @param current 页码
     * @return 商铺列表
     */
    @GetMapping("/of/type")
    public Result queryShopByType(
            @RequestParam("typeId") Integer typeId,
            @RequestParam(value = "current", defaultValue = "1") @Min(1) Integer current,
            @RequestParam(value = "x", required = false) @DecimalMin("-180.0") @DecimalMax("180.0") Double x,
            @RequestParam(value = "y", required = false) @DecimalMin("-90.0") @DecimalMax("90.0") Double y
    ) {
//        // 根据类型分页查询
//        Page<Merchant> page = shopService.query()
//                .eq("type_id", typeId)
//                .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
//        // 返回数据
        return shopService.queryShopByType(typeId, current, x, y);
    }

    /**
     * 附近商户：按与当前坐标的距离升序分页
     * @param x 经度（校区坐标）
     * @param y 纬度（校区坐标）
     * @param current 页码
     * @return 商户列表（含 distance 米）
     */
    @GetMapping("/nearby")
    public Result queryNearby(
            @RequestParam(value = "x", required = false) @DecimalMin("-180.0") @DecimalMax("180.0") Double x,
            @RequestParam(value = "y", required = false) @DecimalMin("-90.0") @DecimalMax("90.0") Double y,
            @RequestParam(value = "current", defaultValue = "1") @Min(1) Integer current
    ) {
        return shopService.queryNearby(x, y, current);
    }

    /**
     * 根据商铺名称关键字分页查询商铺信息
     * @param name 商铺名称关键字
     * @param current 页码
     * @return 商铺列表
     */
    @GetMapping("/of/name")
    public Result queryShopByName(
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "current", defaultValue = "1") @Min(1) Integer current
    ) {
        // 根据类型分页查询
        Page<Merchant> page = shopService.query()
                .like(StrUtil.isNotBlank(name), "name", name)
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 返回数据
        return Result.ok(page.getRecords());
    }

    private Merchant toEntity(MerchantWriteRequest request) {
        return new Merchant().setId(request.getId()).setName(request.getName()).setTypeId(request.getTypeId())
                .setImages(request.getImages()).setArea(request.getArea()).setAddress(request.getAddress())
                .setX(request.getX()).setY(request.getY()).setAvgPrice(request.getAvgPrice())
                .setOpenHours(request.getOpenHours());
    }
}
