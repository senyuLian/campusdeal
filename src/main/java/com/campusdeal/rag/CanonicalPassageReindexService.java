package com.campusdeal.rag;

import com.campusdeal.security.SensitiveLogSanitizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resumable reindexer for the canonical passage model. The service is
 * intentionally separate from the startup index builder so operators can
 * inspect a dry-run count before replacing search traffic.
 */
@Slf4j
@Service
public class CanonicalPassageReindexService {

    private static final int BATCH_SIZE = 50;

    @Resource private DocumentLoader documentLoader;
    @Resource private TextEmbedder embedder;
    @Resource private InMemoryBM25Index bm25Index;
    @Resource private VectorStore vectorStore;

    @Value("${campusdeal.pgvector.enabled:false}")
    private boolean vectorEnabled;
    @Value("${campusdeal.deepseek.api-key:}")
    private String apiKey;
    @Value("${campusdeal.rag.reindex-checkpoint:./data/rag-reindex.checkpoint}")
    private String checkpointPath;

    public ReindexReport reindex(boolean dryRun, boolean resume) {
        List<Document> documents = loadDocuments();
        List<DocumentChunk> chunks = documents.stream()
                .flatMap(document -> documentLoader.chunkDocument(document).stream())
                .toList();
        int start = resume ? readCheckpoint() : 0;
        start = Math.max(0, Math.min(start, chunks.size()));
        if (dryRun) {
            return new ReindexReport(documents.size(), chunks.size(), 0, true, start);
        }

        Map<String, Document> byId = documents.stream()
                .collect(Collectors.toMap(Document::getId, Function.identity(), (left, right) -> left));
        int embedded = 0;
        for (int offset = start; offset < chunks.size(); offset += BATCH_SIZE) {
            List<DocumentChunk> batch = chunks.subList(offset, Math.min(offset + BATCH_SIZE, chunks.size()));
            if (vectorEnabled && apiKey != null && !apiKey.isBlank()) {
                try {
                    List<float[]> embeddings = embedder.embedBatch(batch.stream()
                            .map(DocumentChunk::getText).toList());
                    List<VectorEntry> entries = new ArrayList<>(batch.size());
                    for (int i = 0; i < batch.size() && i < embeddings.size(); i++) {
                        DocumentChunk chunk = batch.get(i);
                        Document doc = byId.get(chunk.getDocId());
                        entries.add(VectorEntry.builder().docId(chunk.getChunkId())
                                .embedding(embeddings.get(i)).metadata(metadata(doc, chunk)).build());
                    }
                    vectorStore.batchInsert(entries);
                    embedded += entries.size();
                } catch (Exception e) {
                    log.warn("Reindex vector batch degraded at offset {}: {}", offset,
                            SensitiveLogSanitizer.exceptionSummary(e));
                }
            }
            for (DocumentChunk chunk : batch) {
                Document doc = byId.get(chunk.getDocId());
                if (doc == null) continue;
                bm25Index.index(Document.builder().id(chunk.getChunkId()).title(doc.getTitle())
                        .content(chunk.getText()).source(doc.getSource()).category(doc.getCategory())
                        .metadata(Map.of("documentId", doc.getId(), "chunkIndex", chunk.getChunkIndex())).build());
            }
            writeCheckpoint(offset + batch.size());
        }
        return new ReindexReport(documents.size(), chunks.size(), embedded, false, chunks.size());
    }

    private List<Document> loadDocuments() {
        List<Document> documents = new ArrayList<>();
        documents.addAll(documentLoader.loadFaqs());
        documents.addAll(documentLoader.loadPolicies());
        documents.addAll(documentLoader.loadMerchants());
        return documents;
    }

    private Map<String, Object> metadata(Document doc, DocumentChunk chunk) {
        return Map.of("docId", doc == null ? "" : doc.getId(),
                "title", doc == null || doc.getTitle() == null ? "" : doc.getTitle(),
                "text", chunk.getText(),
                "source", doc == null || doc.getSource() == null ? "unknown" : doc.getSource(),
                "category", doc == null || doc.getCategory() == null ? "general" : doc.getCategory(),
                "chunkIndex", chunk.getChunkIndex());
    }

    private int readCheckpoint() {
        try {
            if (checkpointPath == null || checkpointPath.isBlank()) return 0;
            return Integer.parseInt(Files.readString(Path.of(checkpointPath), StandardCharsets.UTF_8).trim());
        } catch (Exception ignored) {
            return 0;
        }
    }

    private void writeCheckpoint(int value) {
        try {
            if (checkpointPath == null || checkpointPath.isBlank()) return;
            Path path = Path.of(checkpointPath);
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            Files.writeString(path, Integer.toString(value), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Could not persist reindex checkpoint: {}", SensitiveLogSanitizer.exceptionSummary(e));
        }
    }

    public record ReindexReport(int documentCount, int passageCount, int embeddedCount,
                                boolean dryRun, int nextOffset) { }
}
