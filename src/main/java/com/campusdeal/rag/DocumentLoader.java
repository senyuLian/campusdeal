package com.campusdeal.rag;

import java.util.List;

/**
 * 文档加载器：从文件 / 类路径加载 FAQ、政策、商户介绍，并支持文档分块。
 */
public interface DocumentLoader {

    /** 加载平台 FAQ 文档 */
    List<Document> loadFaqs();

    /** 加载平台政策文档 */
    List<Document> loadPolicies();

    /** 加载商户介绍文档 */
    List<Document> loadMerchants();

    /** 将一篇文档切分为检索单元（chunk） */
    List<DocumentChunk> chunkDocument(Document document);
}
