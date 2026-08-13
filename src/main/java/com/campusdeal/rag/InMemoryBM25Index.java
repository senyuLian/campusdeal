package com.campusdeal.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 纯内存 BM25 稀疏检索索引。
 *
 * <p>基于 中文分词（unigram + bigram）+ TF-IDF，适合 FAQ 场景（文档量 &lt; 10 万）。
 * 查询与文档均做停用词过滤，停用词不影响检索结果。</p>
 */
@Slf4j
@Component
public class InMemoryBM25Index {

    /** 文档 ID → 分词后的词频表 */
    private final Map<String, Map<String, Integer>> docTermFreq = new ConcurrentHashMap<>();
    /** 词 → 出现在几个文档中（文档频次） */
    private final Map<String, Integer> documentFrequency = new ConcurrentHashMap<>();
    /** 文档 ID → 原始文档 */
    private final Map<String, Document> documents = new ConcurrentHashMap<>();

    /** 总文档数（volatile：index 写入 / search 读取可能在不同线程） */
    private volatile int totalDocs = 0;
    private volatile double avgDocLength = 0;

    // BM25 参数
    private static final double K1 = 1.5;   // 词频饱和度
    private static final double B = 0.75;   // 长度归一化

    /** 常见中文停用词（语气词/介词），检索时过滤，避免干扰关键词匹配 */
    private static final java.util.Set<String> STOP_WORDS = java.util.Set.of(
            "的", "了", "吗", "呢", "啊", "吧", "呀", "么", "是", "在", "和", "或", "与", "及",
            "把", "被", "从", "向", "以", "对", "为", "于", "这", "那", "其", "之", "而", "但",
            "我", "你", "他", "她", "它", "我们", "你们", "他们", "她们");

    private static final Pattern ASCII_WORD = Pattern.compile("[a-zA-Z0-9]+");

    /**
     * 中文分词：ASCII 词整体保留（订单号 / 英文），中文按 unigram + bigram。
     * 生产环境可接入 jieba / HanLP 提升切词质量。
     */
    public List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }
        String lower = text.toLowerCase();
        Matcher m = ASCII_WORD.matcher(lower);
        while (m.find()) {
            String word = m.group();
            if (!STOP_WORDS.contains(word)) {
                tokens.add(word);
            }
        }
        // 仅保留中文字符做 n-gram
        String cjk = lower.replaceAll("[^\\u4e00-\\u9fff\\u3400-\\u4dbf]", "");
        for (int i = 0; i < cjk.length(); i++) {
            String uni = cjk.substring(i, i + 1);
            if (!STOP_WORDS.contains(uni)) {
                tokens.add(uni);
            }
            if (i < cjk.length() - 1) {
                String bi = cjk.substring(i, i + 2);
                if (!STOP_WORDS.contains(bi)) {
                    tokens.add(bi);
                }
            }
        }
        return tokens;
    }

    /** 索引一篇文档（重复索引同一 docId 时先撤销旧词频，等效 upsert） */
    public synchronized void index(Document document) {
        if (document == null || document.getId() == null) {
            return;
        }
        // 先移除旧文档的文档频次贡献（幂等 upsert）
        if (documents.containsKey(document.getId())) {
            Map<String, Integer> oldTf = docTermFreq.get(document.getId());
            if (oldTf != null) {
                for (String token : oldTf.keySet()) {
                    int newDf = documentFrequency.merge(token, -1, Integer::sum);
                    if (newDf <= 0) {
                        documentFrequency.remove(token);
                    }
                }
            }
            totalDocs--;
        }
        Map<String, Integer> tf = new HashMap<>();
        for (String token : tokenize((document.getTitle() == null ? "" : document.getTitle())
                + " " + (document.getContent() == null ? "" : document.getContent()))) {
            tf.merge(token, 1, Integer::sum);
        }
        documents.put(document.getId(), document);
        docTermFreq.put(document.getId(), tf);
        for (String token : tf.keySet()) {
            documentFrequency.merge(token, 1, Integer::sum);
        }
        totalDocs++;
        recomputeAvgDocLength();
    }

    /** 删除文档 */
    public synchronized void delete(String docId) {
        Map<String, Integer> tf = docTermFreq.remove(docId);
        documents.remove(docId);
        if (tf != null) {
            for (String token : tf.keySet()) {
                int newDf = documentFrequency.merge(token, -1, Integer::sum);
                if (newDf <= 0) {
                    documentFrequency.remove(token);
                }
            }
            totalDocs--;
            recomputeAvgDocLength();
        }
    }

    /**
     * BM25 检索：query 分词后对每个文档累计打分，按分数降序返回 topK。
     */
    public List<BM25Result> search(String query, int topK) {
        List<String> queryTokens = tokenize(query);
        Map<String, Double> scores = new HashMap<>();
        int docs = totalDocs;
        double avgLen = avgDocLength;

        for (Map.Entry<String, Map<String, Integer>> entry : docTermFreq.entrySet()) {
            String docId = entry.getKey();
            Map<String, Integer> termFreq = entry.getValue();
            int docLen = 0;
            for (int f : termFreq.values()) {
                docLen += f;
            }
            double score = 0;
            for (String token : queryTokens) {
                int tf = termFreq.getOrDefault(token, 0);
                if (tf == 0) {
                    continue;
                }
                int df = documentFrequency.getOrDefault(token, 0);
                if (df == 0 || docs == 0 || avgLen <= 0) {
                    continue;
                }
                double idf = Math.log(1 + (docs - df + 0.5) / (df + 0.5));
                double numerator = tf * (K1 + 1);
                double denominator = tf + K1 * (1 - B + B * docLen / avgLen);
                score += idf * numerator / denominator;
            }
            if (score > 0) {
                scores.put(docId, score);
            }
        }

        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> {
                    Document doc = documents.get(e.getKey());
                    return BM25Result.builder()
                            .docId(doc.getId())
                            .title(doc.getTitle())
                            .content(doc.getContent())
                            .score(e.getValue())
                            .source(doc.getSource())
                            .build();
                })
                .collect(Collectors.toList());
    }

    public int size() {
        return totalDocs;
    }

    private void recomputeAvgDocLength() {
        if (totalDocs == 0) {
            avgDocLength = 0;
            return;
        }
        long total = 0;
        for (Map<String, Integer> tf : docTermFreq.values()) {
            for (int f : tf.values()) {
                total += f;
            }
        }
        avgDocLength = (double) total / totalDocs;
    }
}
