package com.campusdeal.mq;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import org.apache.kafka.common.TopicPartition;

/**
 * Kafka 消费者注解驱动配置
 */
@Configuration
@EnableKafka
@ConditionalOnProperty(prefix = "campusdeal.kafka", name = "enabled", havingValue = "true")
public class KafkaConfig {

    private static final long RETRY_INTERVAL_MS = 1_000L;
    private static final long MAX_RETRIES = 3L;

    /**
     * Batch listener failures must escape the listener method so offsets stay
     * uncommitted during bounded retries. Exhausted records are published to
     * a deterministic dead-letter topic for operator replay.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaOperations<Object, Object> operations) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                operations,
                (record, exception) -> new TopicPartition(record.topic() + ".DLT", record.partition()));
        return new DefaultErrorHandler(recoverer, new FixedBackOff(RETRY_INTERVAL_MS, MAX_RETRIES));
    }

    @Bean(name = "kafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, FlashDealOrderMessage>
    kafkaListenerContainerFactory(ConsumerFactory<String, FlashDealOrderMessage> consumerFactory,
                                  DefaultErrorHandler kafkaErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, FlashDealOrderMessage> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setBatchListener(true);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        return factory;
    }
}
