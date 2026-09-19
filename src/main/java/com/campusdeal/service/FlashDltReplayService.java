package com.campusdeal.service;

import cn.hutool.json.JSONUtil;
import com.campusdeal.dto.FlashDltReplayRequest;
import com.campusdeal.dto.Result;
import com.campusdeal.entity.FlashDltReplayAudit;
import com.campusdeal.exception.ValidationException;
import com.campusdeal.mapper.FlashDltReplayAuditMapper;
import com.campusdeal.mq.FlashDealConsumer;
import com.campusdeal.mq.FlashDealOrderMessage;
import com.campusdeal.security.AuthorizationService;
import com.campusdeal.security.SensitiveLogSanitizer;
import com.campusdeal.utils.UserHolder;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Validated, audited operator entry point for replaying one DLT event. */
@Service
@ConditionalOnProperty(prefix = "campusdeal.kafka", name = "enabled", havingValue = "true")
public class FlashDltReplayService {

    @Resource
    private AuthorizationService authorizationService;
    @Resource
    private FlashDltReplayAuditMapper auditMapper;
    @Resource
    private FlashDealConsumer flashDealConsumer;

    public Result replay(FlashDltReplayRequest request) {
        authorizationService.requireAdmin();
        if (request == null || request.getEventId() == null || request.getPayload() == null) {
            throw new ValidationException("DLT 事件参数不能为空");
        }
        FlashDealOrderMessage message;
        try {
            message = JSONUtil.toBean(request.getPayload(), FlashDealOrderMessage.class);
        } catch (Exception e) {
            throw new ValidationException("DLT 事件格式不合法");
        }
        if (message == null || message.getOrderId() == null || message.getUserId() == null
                || message.getDealId() == null) {
            throw new ValidationException("DLT 订单事件缺少必要字段");
        }

        Long operatorId = UserHolder.getUser().getId();
        FlashDltReplayAudit audit = new FlashDltReplayAudit();
        audit.setEventId(request.getEventId());
        audit.setOperatorUserId(operatorId);
        audit.setPayloadSha256(sha256(request.getPayload()));
        audit.setStatus("PROCESSING");
        if (auditMapper.insertIfAbsent(audit) != 1) {
            return Result.ok(java.util.Map.of("eventId", request.getEventId(), "status", "ALREADY_REPLAYED"));
        }
        try {
            flashDealConsumer.processMessage(message);
            auditMapper.updateStatus(request.getEventId(), "SUCCEEDED", null);
            return Result.ok(java.util.Map.of("eventId", request.getEventId(), "status", "SUCCEEDED",
                    "orderId", message.getOrderId().toString()));
        } catch (RuntimeException e) {
            auditMapper.updateStatus(request.getEventId(), "FAILED", safeMessage(e));
            throw e;
        }
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) return "REPLAY_FAILED";
        return SensitiveLogSanitizer.redact(message);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("cannot hash DLT payload", e);
        }
    }
}
