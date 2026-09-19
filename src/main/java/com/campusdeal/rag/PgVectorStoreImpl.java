package com.campusdeal.rag;

import cn.hutool.json.JSONUtil;
import com.campusdeal.security.SensitiveLogSanitizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * PGVector 向量存储实现。
 *
 * <p>通过 JdbcTemplate 执行 pgvector 扩展的 {@code <=>} 余弦距离操作符。
 * JdbcTemplate 懒加载：未配置 {@code campusdeal.pgvector.url} 时向量检索禁用（应用照常启动），
 * 单元测试通过 Mock JdbcTemplate 验证 SQL 逻辑，无需真实 PostgreSQL。</p>
 */
@Slf4j
@Component
public class PgVectorStoreImpl implements VectorStore {

    private static final String INSERT_SQL = """
        INSERT INTO document_vectors (id, title, content, embedding, source, category, metadata)
        VALUES (?, ?, ?, ?::vector, ?, ?, ?::jsonb)
        ON CONFLICT (id) DO UPDATE SET
            embedding = EXCLUDED.embedding,
            content = EXCLUDED.content,
            source = EXCLUDED.source,
            category = EXCLUDED.category,
            metadata = EXCLUDED.metadata,
            updated_at = CURRENT_TIMESTAMP
        """;

    private static final String SEARCH_SQL = """
        SELECT id, content, metadata,
               1 - (embedding <=> ?::vector) AS similarity
        FROM document_vectors
        ORDER BY embedding <=> ?::vector
        LIMIT ?
        """;

    private static final RowMapper<VectorResult> ROW_MAPPER = (rs, rowNum) -> {
        VectorResult.VectorResultBuilder builder = VectorResult.builder()
                .docId(rs.getString("id"))
                .content(rs.getString("content"))
                .cosineSimilarity(rs.getDouble("similarity"));
        try {
            String metadata = rs.getString("metadata");
            if (metadata != null && !metadata.isBlank()) {
                Map<?, ?> values = JSONUtil.parseObj(metadata);
                Object title = values.get("title");
                Object source = values.get("source");
                Object category = values.get("category");
                builder.title(title == null ? "" : title.toString())
                        .source(source == null ? "" : source.toString())
                        .category(category == null ? "" : category.toString());
            }
        } catch (Exception ignored) {
            // Metadata is enrichment; the canonical id/content remain usable.
        }
        return builder.build();
    };

    @Value("${campusdeal.pgvector.url:}")
    private String url;

    @Value("${campusdeal.pgvector.enabled:false}")
    private boolean enabled;

    @Value("${campusdeal.pgvector.username:}")
    private String username;

    @Value("${campusdeal.pgvector.password:}")
    private String password;

    /** 测试注入点：@InjectMocks 会注入 mock；生产环境懒加载 */
    JdbcTemplate jdbcTemplate;

    @Override
    public List<VectorResult> search(float[] embedding, int topK) {
        JdbcTemplate jt = resolveTemplate();
        if (jt == null) {
            return List.of();
        }
        String vectorStr = embeddingToString(embedding);
        try {
            return jt.query(SEARCH_SQL, ROW_MAPPER, vectorStr, vectorStr, topK);
        } catch (Exception e) {
            log.warn("PGVector 向量检索失败（数据源未就绪？）: {}", SensitiveLogSanitizer.exceptionSummary(e));
            return List.of();
        }
    }

    @Override
    public void insert(String docId, float[] embedding, Map<String, Object> metadata) {
        JdbcTemplate jt = resolveTemplate();
        if (jt == null) {
            return;
        }
        try {
            jt.update(INSERT_SQL, insertArgs(docId, embedding, metadata));
        } catch (Exception e) {
            log.warn("PGVector 插入失败: {}", SensitiveLogSanitizer.exceptionSummary(e));
        }
    }

    @Override
    public void batchInsert(List<VectorEntry> entries) {
        JdbcTemplate jt = resolveTemplate();
        if (jt == null || entries == null || entries.isEmpty()) {
            return;
        }
        List<Object[]> batchArgs = new ArrayList<>(entries.size());
        for (VectorEntry e : entries) {
            batchArgs.add(insertArgs(e.getDocId(), e.getEmbedding(), e.getMetadata()));
        }
        try {
            jt.batchUpdate(INSERT_SQL, batchArgs);
        } catch (Exception e) {
            log.warn("PGVector 批量插入失败: {}", SensitiveLogSanitizer.exceptionSummary(e));
        }
    }

    @Override
    public void delete(String docId) {
        JdbcTemplate jt = resolveTemplate();
        if (jt == null) {
            return;
        }
        try {
            jt.update("DELETE FROM document_vectors WHERE id = ?", docId);
        } catch (Exception e) {
            log.warn("PGVector 删除失败: {}", SensitiveLogSanitizer.exceptionSummary(e));
        }
    }

    private Object[] insertArgs(String docId, float[] embedding, Map<String, Object> metadata) {
        Map<String, Object> meta = metadata == null ? Map.of() : metadata;
        return new Object[]{
                docId,
                meta.getOrDefault("title", ""),
                meta.getOrDefault("text", ""),
                embeddingToString(embedding),
                meta.getOrDefault("source", "unknown"),
                meta.getOrDefault("category", "general"),
                JSONUtil.toJsonStr(meta)
        };
    }

    private JdbcTemplate resolveTemplate() {
        if (jdbcTemplate != null) {
            return jdbcTemplate;
        }
        synchronized (this) {
            if (jdbcTemplate == null) {
                if (!enabled || url == null || url.isBlank()) {
                    log.debug("PGVector disabled by configuration; BM25 remains available");
                    return null;
                }
                DriverManagerDataSource ds = new DriverManagerDataSource(url, username, password);
                jdbcTemplate = new JdbcTemplate(ds);
            }
        }
        return jdbcTemplate;
    }

    /** 将 float[] 转为 pgvector 文本格式，如 {@code [0.1,0.2,0.3]} */
    static String embeddingToString(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(embedding[i]);
        }
        sb.append("]");
        return sb.toString();
    }
}
