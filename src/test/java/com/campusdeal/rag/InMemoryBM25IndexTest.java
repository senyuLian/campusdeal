package com.campusdeal.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BM-01..03：BM25 稀疏检索索引单元测试（纯内存，无外部依赖）。
 */
class InMemoryBM25IndexTest {

    private InMemoryBM25Index index;

    @BeforeEach
    void setUp() {
        index = new InMemoryBM25Index();
    }

    private Document doc(String id, String title, String content) {
        return Document.builder().id(id).title(title).content(content).source("faq").category("faq").build();
    }

    @Test
    @DisplayName("BM-01 精确匹配：3 篇退款文档全部返回，'如何申请退款'排第一")
    void bm01_exactMatchRanking() {
        index.index(doc("faq-1", "如何申请退款", "如何申请退款 校园卡退款 余额退回 退款到账时间"));
        index.index(doc("faq-2", "退款流程说明", "退款流程说明 提交申请后处理 退款到账时间"));
        index.index(doc("faq-3", "退款到账时间", "退款到账时间 一般三个工作日 退款处理中"));

        List<BM25Result> results = index.search("退款", 5);

        assertThat(results).hasSize(3);
        assertThat(results.get(0).getDocId()).isEqualTo("faq-1");
        assertThat(results.get(0).getScore()).isPositive();
        assertThat(results).extracting(BM25Result::getSource).containsOnly("faq");
    }

    @Test
    @DisplayName("BM-02 空索引：0 篇文档时返回空列表")
    void bm02_emptyIndex() {
        assertThat(index.search("退款", 5)).isEmpty();
    }

    @Test
    @DisplayName("BM-03 停用词处理：文档含'的/了/吗'，检索'怎么退款'不受影响")
    void bm03_stopWordsDontAffectResults() {
        index.index(doc("faq-1", "退款", "退款是什么？申请后怎么处理呢？退款多久到账呢？"));
        index.index(doc("faq-2", "配送", "配送范围是哪里？可以送到宿舍吗？"));

        List<BM25Result> results = index.search("怎么退款", 5);

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).getDocId()).isEqualTo("faq-1");
    }

    @Test
    @DisplayName("BM-04 删除文档后检索不到")
    void bm04_delete() {
        index.index(doc("faq-1", "退款", "退款申请后原路返回"));
        index.delete("faq-1");
        assertThat(index.search("退款", 5)).isEmpty();
        assertThat(index.size()).isZero();
    }

    @Test
    @DisplayName("BM-05 大小写/中英混合：英文词整体保留且不区分大小写，中文 unigram+bigram 可检索")
    void bm05_mixedCaseChineseEnglish() {
        // 含英文（大小写混合）、订单号、中文关键词的文档
        index.index(doc("faq-1", "Refund iPhone 订单", "申请 iPhone 退款，ApplePay 原路返回，订单号 ORDER-12345"));
        index.index(doc("faq-2", "安卓机退款", "Android 手机退款流程"));

        // 大小写无关：小写查询也能命中大写关键词
        List<BM25Result> caseInsensitive = index.search("iphone", 5);
        assertThat(caseInsensitive).isNotEmpty();
        assertThat(caseInsensitive.get(0).getDocId()).isEqualTo("faq-1");

        // 订单号整体保留：子串不误命中
        List<BM25Result> orderNo = index.search("ORDER-12345", 5);
        assertThat(orderNo).isNotEmpty();
        assertThat(orderNo.get(0).getDocId()).isEqualTo("faq-1");

        // 中英混排文档：中文关键词命中（两篇都含"退款"，较短文档按 BM25 长度归一化排前，
        // 断言混排文档在结果内即可；唯一 bigram 查询应把混排文档排第一）
        List<BM25Result> zh = index.search("退款", 5);
        assertThat(zh).extracting(BM25Result::getDocId).contains("faq-1");

        List<BM25Result> zhUnique = index.search("申请退款", 5);
        assertThat(zhUnique).isNotEmpty();
        assertThat(zhUnique.get(0).getDocId()).isEqualTo("faq-1");
    }
}
