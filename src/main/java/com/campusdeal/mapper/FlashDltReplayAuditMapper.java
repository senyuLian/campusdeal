package com.campusdeal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campusdeal.entity.FlashDltReplayAudit;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface FlashDltReplayAuditMapper extends BaseMapper<FlashDltReplayAudit> {

    @Insert("INSERT IGNORE INTO flash_dlt_replay_audit "
            + "(event_id, operator_user_id, payload_sha256, status, created_at, updated_at) "
            + "VALUES(#{eventId}, #{operatorUserId}, #{payloadSha256}, 'PROCESSING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")
    int insertIfAbsent(FlashDltReplayAudit audit);

    @Update("UPDATE flash_dlt_replay_audit SET status=#{status}, error_message=#{errorMessage}, "
            + "updated_at=CURRENT_TIMESTAMP WHERE event_id=#{eventId}")
    int updateStatus(@Param("eventId") String eventId, @Param("status") String status,
                     @Param("errorMessage") String errorMessage);
}
