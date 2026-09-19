package com.campusdeal.mapper;

import com.campusdeal.entity.FlashDeal;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Update;

/**
 * <p>
 * 秒杀优惠券表，与优惠券是一对一关系 Mapper 接口
 * </p>
 *
 * @author 虎哥
 * @since 2022-01-04
 */
public interface FlashDealMapper extends BaseMapper<FlashDeal> {

    @Update("UPDATE tb_seckill_voucher SET stock = stock - 1, update_time = CURRENT_TIMESTAMP WHERE voucher_id = #{dealId} AND stock > 0")
    int decrementStock(@org.apache.ibatis.annotations.Param("dealId") Long dealId);

    @Update("UPDATE tb_seckill_voucher SET stock = stock + 1, update_time = CURRENT_TIMESTAMP WHERE voucher_id = #{dealId}")
    int incrementStock(@org.apache.ibatis.annotations.Param("dealId") Long dealId);

}
