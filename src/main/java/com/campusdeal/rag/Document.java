package com.campusdeal.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 知识索引的原始文档模型。
 *
 * <p>{@code source} 表示来源（faq / merchant_policy / campus_rule），
 * {@code category} 表示业务分类（退款 / 配送 / 账户 / 优惠券 …），
 * 两者会进入 PGVector 元数据列，便于检索时按来源过滤。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Document {

    private String id;
    private String title;
    private String content;
    private String source;
    private String category;
    private Map<String, Object> metadata;
}
