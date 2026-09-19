package com.campusdeal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campusdeal.entity.FlashOrderIntent;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Insert;

/** Mapper for durable flash-deal acceptance intents. */
public interface FlashOrderIntentMapper extends BaseMapper<FlashOrderIntent> {

    @Insert("INSERT IGNORE INTO flash_order_intent(order_id, user_id, voucher_id, status, accepted_at, update_time) "
            + "VALUES(#{orderId}, #{userId}, #{voucherId}, #{status}, #{acceptedAt}, CURRENT_TIMESTAMP)")
    int insertIgnoreIntent(FlashOrderIntent intent);

    @Update("UPDATE flash_order_intent SET status = #{status}, failure_reason = #{reason}, "
            + "update_time = CURRENT_TIMESTAMP WHERE order_id = #{orderId} "
            + "AND status IN ('ACCEPTED','PROCESSING')")
    int advanceStatus(@Param("orderId") Long orderId, @Param("status") String status,
                      @Param("reason") String reason);
}
