package com.campusdeal.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Kafka Producer 单元测试（KP-01..03）
 *
 * <p>Mock KafkaTemplate，不依赖 Kafka 服务。</p>
 *
 * <p>异步落库改造后新契约：send() 为<b>同步确认 + 有界超时</b>，
 * 成功返回 true、失败返回 false、不抛异常；调用方据返回值决定是否写 Outbox 补偿。</p>
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
    @DisplayName("KP-01: 同步确认发送成功——返回 true，topic/key/messageId header 正确")
    void shouldSendSuccessfully() {
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        boolean result = producer.send(message());

        assertThat(result).isTrue();

        ArgumentCaptor<ProducerRecord<String, FlashDealOrderMessage>> cap = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(cap.capture());
        ProducerRecord<String, FlashDealOrderMessage> record = cap.getValue();
        assertThat(record.topic()).isEqualTo("flash-deal-orders");
        assertThat(record.key()).isEqualTo("1001");  // key = userId，保证同用户有序
        assertThat(record.value().getOrderId()).isEqualTo(1L);
        assertThat(record.headers().lastHeader("messageId")).isNotNull();
    }

    @Test
    @DisplayName("KP-02: 发送失败——返回 false 不抛异常（调用方写 Outbox 补偿）")
    void shouldReturnFalseOnFailure() {
        CompletableFuture<SendResult<String, FlashDealOrderMessage>> future = new CompletableFuture<>();
        future.completeExceptionally(new KafkaException("broker down"));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(future);

        // 投递失败：吞掉异常、返回 false，交由调用方写 Outbox PENDING
        boolean result = producer.send(message());

        assertThat(result).isFalse();
        verify(kafkaTemplate).send(any(ProducerRecord.class));
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
