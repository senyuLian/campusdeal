package com.campusdeal.service;

import com.campusdeal.dto.FlashDltReplayRequest;
import com.campusdeal.dto.UserDTO;
import com.campusdeal.mapper.FlashDltReplayAuditMapper;
import com.campusdeal.mq.FlashDealConsumer;
import com.campusdeal.security.AuthorizationService;
import com.campusdeal.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FlashDltReplayServiceTest {

    @Mock AuthorizationService authorizationService;
    @Mock FlashDltReplayAuditMapper auditMapper;
    @Mock FlashDealConsumer flashDealConsumer;
    @InjectMocks FlashDltReplayService service;

    @AfterEach
    void clearUser() {
        UserHolder.removeUser();
    }

    @Test
    void replaysOnceAndRecordsOperatorAudit() {
        UserDTO operator = new UserDTO();
        operator.setId(7L);
        operator.setRole("ADMIN");
        UserHolder.saveUser(operator);
        when(auditMapper.insertIfAbsent(any())).thenReturn(1);

        FlashDltReplayRequest request = request("evt-1");
        var result = service.replay(request);

        assertThat(result.getSuccess()).isTrue();
        verify(authorizationService).requireAdmin();
        verify(auditMapper).insertIfAbsent(any());
        verify(flashDealConsumer).processMessage(any());
        verify(auditMapper).updateStatus("evt-1", "SUCCEEDED", null);
    }

    @Test
    void duplicateEventIsNotProcessedAgain() {
        UserDTO operator = new UserDTO();
        operator.setId(7L);
        UserHolder.saveUser(operator);
        when(auditMapper.insertIfAbsent(any())).thenReturn(0);

        var result = service.replay(request("evt-1"));

        assertThat(result.getData().toString()).contains("ALREADY_REPLAYED");
        verify(flashDealConsumer, never()).processMessage(any());
        verify(auditMapper, never()).updateStatus(any(), any(), any());
    }

    private FlashDltReplayRequest request(String eventId) {
        FlashDltReplayRequest request = new FlashDltReplayRequest();
        request.setEventId(eventId);
        request.setPayload("{\"orderId\":1,\"userId\":2,\"dealId\":3,\"timestamp\":4}");
        return request;
    }
}
