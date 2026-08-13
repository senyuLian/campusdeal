package com.campusdeal.agent.tool;

import cn.hutool.json.JSONUtil;
import com.campusdeal.agent.Tool;
import com.campusdeal.rag.HybridRetriever;
import com.campusdeal.rag.RetrievalResult;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * FAQ 检索工具：调用 RAG 混合检索（BM25 + 向量 + RRF 融合）从知识库查询答案。
 */
@Component
public class SearchFaqTool {

    @Resource
    private HybridRetriever hybridRetriever;

    @Tool(name = "search_faq",
            description = "搜索平台常见问题（FAQ）知识库，获取平台规则、使用帮助等信息。适用于用户询问'怎么用'、'有什么功能'、'退款规则'等问题")
    public String searchFaq(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return "{\"error\":\"keyword 不能为空\"}";
        }
        List<RetrievalResult> results = hybridRetriever.retrieve(keyword, 5);
        return JSONUtil.toJsonStr(results);
    }
}
