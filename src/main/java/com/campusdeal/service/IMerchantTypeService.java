package com.campusdeal.service;

import com.baomidou.mybatisplus.core.conditions.interfaces.Func;
import com.campusdeal.entity.MerchantType;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IMerchantTypeService extends IService<MerchantType> {


    List<MerchantType> queryList();
}
