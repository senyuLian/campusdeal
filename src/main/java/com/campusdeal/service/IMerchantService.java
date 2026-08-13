package com.campusdeal.service;

import com.campusdeal.dto.Result;
import com.campusdeal.entity.Merchant;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IMerchantService extends IService<Merchant> {

    Result queryById(Long id);

    Result updateShop(Merchant merchant);

    Result queryShopByType(Integer typeId, Integer current, Double x, Double y);

    /** 附近商户：按与给定坐标的距离升序分页（首页金刚区「附近」入口） */
    Result queryNearby(Double x, Double y, Integer current);
}
