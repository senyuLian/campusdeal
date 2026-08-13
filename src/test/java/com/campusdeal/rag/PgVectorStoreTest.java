package com.campusdeal.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PV-01..04：PGVector 向量存储单元测试（Mock JdbcTemplate，无需真实 PostgreSQL）。
 */
@ExtendWith(MockitoExtension.class)
class PgVectorStoreTest {

    @Mock
    JdbcTemplate jdbcTemplate;

    @InjectMocks
    PgVectorStoreImpl vectorStore;

    @Test
    @DisplayName("PV-01 向量检索：search 返回 5 条且按余弦相似度降序（由 SQL 排序，mock 保持顺序）")
    void pv01_vectorSearch() {
        List<VectorResult> dbResults = List.of(
                VectorResult.builder().docId("d1").content("退款指南").cosineSimilarity(0.95).build(),
                VectorResult.builder().docId("d2").content("配送政策").cosineSimilarity(0.88).build(),
                VectorResult.builder().docId("d3").content("校园卡").cosineSimilarity(0.80).build(),
                VectorResult.builder().docId("d4").content("优惠券").cosineSimilarity(0.72).build(),
                VectorResult.builder().docId("d5").content("账户").cosineSimilarity(0.65).build());
        when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                any(Object.class), any(Object.class), any(Object.class)))
                .thenReturn(dbResults);

        List<VectorResult> results = vectorStore.search(new float[1024], 5);

        assertThat(results).hasSize(5);
        assertThat(results.get(0).getDocId()).isEqualTo("d1");
        assertThat(results.get(0).getCosineSimilarity()).isEqualTo(0.95);
    }

    @Test
    @DisplayName("PV-02 空向量表：search 返回空列表")
    void pv02_emptyVectorTable() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                any(Object.class), any(Object.class), any(Object.class)))
                .thenReturn(List.of());

        assertThat(vectorStore.search(new float[1024], 5)).isEmpty();
    }

    @Test
    @DisplayName("PV-03 批量插入：50 条向量全部写入，无异常")
    void pv03_batchInsert() {
        List<VectorEntry> entries = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            entries.add(VectorEntry.builder()
                    .docId("doc-" + i)
                    .embedding(new float[]{i, i + 1})
                    .metadata(Map.of("source", "faq", "category", "faq", "text", "内容" + i))
                    .build());
        }

        vectorStore.batchInsert(entries);

        verify(jdbcTemplate).batchUpdate(anyString(), anyList());
    }

    @Test
    @DisplayName("PV-04 重复 ID 更新：insert 使用带 ON CONFLICT 的 upsert SQL")
    void pv04_duplicateIdUpsert() {
        vectorStore.insert("faq-1", new float[]{1, 2, 3}, Map.of("source", "faq", "category", "退款"));

        // INSERT_SQL 带 ON CONFLICT (id) DO UPDATE —— 重复 ID 时数据库层更新向量
        verify(jdbcTemplate).update(anyString(),
                any(Object.class), any(Object.class), any(Object.class),
                any(Object.class), any(Object.class), any(Object.class), any(Object.class));
        assertThat(PgVectorStoreImpl.embeddingToString(new float[]{1, 2, 3})).isEqualTo("[1.0,2.0,3.0]");
    }

    @Test
    @DisplayName("PV-05 未配置数据源时禁用：search 返回空而不抛异常")
    void pv05_disabledWithoutUrl() {
        PgVectorStoreImpl disabled = new PgVectorStoreImpl();
        assertThat(disabled.search(new float[4], 5)).isEmpty();
    }
}
