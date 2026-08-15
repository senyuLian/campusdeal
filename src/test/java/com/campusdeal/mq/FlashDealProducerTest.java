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
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Kafka Producer 单元测试（KP-01..03）
 *
 * <p>Mock KafkaTemplate，不依赖 Kafka 服务。</p>
 *
 * <p>P0-1 改造后新契约：send() 为<b>异步尽力而为</b>（fire-and-forget），
 * 返回 null、不抛异常；Kafka 发送下沉到专用后台线程池，主流程不受 Kafka 可用性影响。
 * 故 KP-01/02 断言方式改为：验证 KafkaTemplate.send 被异步触达 + send 返回 null。</p>
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
    @DisplayName("KP-01: 异步发送成功——send 返回 null，后台线程触达 KafkaTemplate")
    void shouldSendSuccessfully() {
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        SendResult<String, FlashDealOrderMessage> result = producer.send(message());

        // 新契约：不等待 Kafka metadata，立即返回 null
        assertThat(result).isNull();

        // 后台线程异步触达 KafkaTemplate（timeout 等待异步执行）
        ArgumentCaptor<ProducerRecord<String, FlashDealOrderMessage>> cap = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate, timeout(500)).send(cap.capture());
        ProducerRecord<String, FlashDealOrderMessage> record = cap.getValue();
        assertThat(record.topic()).isEqualTo("flash-deal-orders");
        assertThat(record.key()).isEqualTo("1001");  // key = userId，保证同用户有序
        assertThat(record.value().getOrderId()).isEqualTo(1L);
        assertThat(record.headers().lastHeader("messageId")).isNotNull();
    }

    @Test
    @DisplayName("KP-02: 发送失败被吞掉——send 返回 null 不抛异常（降级契约）")
    void shouldSwallowFailureAsync() {
        CompletableFuture<SendResult<String, FlashDealOrderMessage>> future = new CompletableFuture<>();
        future.completeExceptionally(new KafkaException("broker down"));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(future);

        // 即使底层 future 异常完成，send 也不抛异常、返回 null（订单已同步落库兜底）
        SendResult<String, FlashDealOrderMessage> result = producer.send(message());

        assertThat(result).isNull();
        verify(kafkaTemplate, timeout(500)).send(any(ProducerRecord.class));
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
