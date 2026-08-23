package com.campusdeal.mapper;

import com.campusdeal.entity.CouponOrder;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface CouponOrderMapper extends BaseMapper<CouponOrder> {

    /**
     * 批量落库（单条多行 INSERT IGNORE）。
     * <p>IGNORE 语义：命中主键或 uk_user_voucher 冲突时静默跳过，避免整批回滚。
     * 配合 Redis SETNX 快速去重，MySQL UNIQUE KEY 作最终幂等兜底。</p>
     *
     * @param orders 待落库订单列表
     * @return 实际插入行数（被 IGNORE 跳过的重复行不计入）
     */
    @Insert("<script>" +
            "INSERT IGNORE INTO tb_voucher_order (id, user_id, voucher_id, status, create_time) VALUES " +
            "<foreach collection='list' item='o' separator=','>" +
            "(#{o.id}, #{o.userId}, #{o.voucherId}, #{o.status}, #{o.createTime})" +
            "</foreach>" +
            "</script>")
    int batchInsertIgnore(@Param("list") List<CouponOrder> orders);
}
