package com.campusdeal.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Kafka Producer 单元测试（KP-01..03）
 *
 * <p>Mock KafkaTemplate，不依赖 Kafka 服务。</p>
 */
@ExtendWith(MockitoExtension.class)
class FlashDealProducerTest {

    @Mock
    private KafkaTemplate<String, FlashDealOrderMessage> kafkaTemplate;

    @InjectMocks
    private FlashDealProducerImpl producer;

    @BeforeEach
    void setUp() {
        // @Value 字段在纯单元测试中不会被注入，手动设置 topic
        ReflectionTestUtils.setField(producer, "topic", "flash-deal-orders");
    }

    private FlashDealOrderMessage message() {
        return new FlashDealOrderMessage(1L, 1001L, 5L, 123L);
    }

    @Test
    @DisplayName("KP-01: 正常发送返回 SendResult")
    void shouldSendSuccessfully() {
        SendResult<String, FlashDealOrderMessage> sendResult = mock(SendResult.class);
        RecordMetadata metadata = mock(RecordMetadata.class);
        when(metadata.offset()).thenReturn(100L);
        when(metadata.partition()).thenReturn(0);
        when(sendResult.getRecordMetadata()).thenReturn(metadata);
        CompletableFuture<SendResult<String, FlashDealOrderMessage>> future =
                CompletableFuture.completedFuture(sendResult);
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(future);

        SendResult<String, FlashDealOrderMessage> result = producer.send(message());

        assertThat(result).isSameAs(sendResult);
        verify(kafkaTemplate).send(any(ProducerRecord.class));
    }

    @Test
    @DisplayName("KP-02: 发送失败抛出 KafkaException")
    void shouldThrowKafkaExceptionOnFailure() {
        CompletableFuture<SendResult<String, FlashDealOrderMessage>> future =
                new CompletableFuture<>();
        future.completeExceptionally(new KafkaException("broker down"));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(future);

        assertThatThrownBy(() -> producer.send(message()))
                .isInstanceOf(KafkaException.class);
    }

    @Test
    @DisplayName("KP-03: 消息体可被 Jackson 正确序列化/反序列化")
    void shouldSerializeMessageCorrectly() throws Exception {
        FlashDealOrderMessage msg = message();

        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(msg);

        assertThat(json)
                .contains("\"orderId\":1")
                .contains("\"userId\":1001")
                .contains("\"dealId\":5")
                .contains("\"timestamp\":123");

        FlashDealOrderMessage back = mapper.readValue(json, FlashDealOrderMessage.class);
        assertThat(back).isEqualTo(msg);
    }
}
