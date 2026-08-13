package com.campusdeal.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 从类路径资源加载 FAQ / 政策 / 商户介绍文档，并按固定块长切分。
 *
 * <p>资源约定（均可通过替换文件扩展）：{@code rag/faq/*.md}、{@code rag/policy/*.md}、{@code rag/merchant/*.md}。
 * 文件首行 {@code # 标题} 作为文档标题，其余为正文。</p>
 */
@Slf4j
@Component
public class ClasspathDocumentLoader implements DocumentLoader {

    public static final int CHUNK_SIZE = 300;
    public static final int CHUNK_OVERLAP = 50;

    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    @Override
    public List<Document> loadFaqs() {
        return loadFrom("rag/faq/*", "faq", "faq");
    }

    @Override
    public List<Document> loadPolicies() {
        return loadFrom("rag/policy/*", "merchant_policy", "policy");
    }

    @Override
    public List<Document> loadMerchants() {
        return loadFrom("rag/merchant/*", "merchant", "merchant");
    }

    private List<Document> loadFrom(String location, String source, String defaultCategory) {
        List<Document> docs = new ArrayList<>();
        try {
            Resource[] resources = resolver.getResources("classpath:" + location);
            for (Resource r : resources) {
                String name = r.getFilename();
                if (name == null || !name.endsWith(".md")) {
                    continue;
                }
                String id = source + "-" + name.substring(0, name.length() - 3);
                String raw = new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                String title = extractTitle(name, raw);
                String content = stripHeading(raw);
                docs.add(Document.builder()
                        .id(id)
                        .title(title)
                        .content(content)
                        .source(source)
                        .category(defaultCategory)
                        .build());
            }
        } catch (IOException e) {
            log.warn("加载 RAG 文档失败（目录可能为空）: {} -> {}", location, e.getMessage());
        }
        return docs;
    }

    private String extractTitle(String filename, String raw) {
        for (String line : raw.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("# ")) {
                return trimmed.substring(2).trim();
            }
        }
        String base = filename.substring(0, filename.length() - 3);
        return base;
    }

    private String stripHeading(String raw) {
        StringBuilder sb = new StringBuilder();
        for (String line : raw.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("# ")) {
                continue;
            }
            sb.append(trimmed).append('\n');
        }
        return sb.toString().trim();
    }

    @Override
    public List<DocumentChunk> chunkDocument(Document document) {
        List<DocumentChunk> chunks = new ArrayList<>();
        String text = document.getContent();
        if (text == null || text.isBlank()) {
            return chunks;
        }
        int start = 0;
        int idx = 0;
        while (start < text.length()) {
            int end = Math.min(start + CHUNK_SIZE, text.length());
            // 尽量在句号处断开，避免切断语义
            if (end < text.length()) {
                int boundary = text.lastIndexOf('。', end);
                if (boundary > start + CHUNK_SIZE / 2) {
                    end = boundary + 1;
                }
            }
            String chunkText = text.substring(start, end).trim();
            if (!chunkText.isEmpty()) {
                chunks.add(DocumentChunk.builder()
                        .chunkId(document.getId() + "-c" + idx)
                        .docId(document.getId())
                        .text(chunkText)
                        .chunkIndex(idx++)
                        .build());
            }
            // 带重叠前进，保证跨块语义连贯
            start = Math.max(end - CHUNK_OVERLAP, start + 1);
        }
        return chunks;
    }
}
