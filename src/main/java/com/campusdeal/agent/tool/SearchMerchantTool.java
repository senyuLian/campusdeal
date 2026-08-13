package com.campusdeal.agent.tool;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.campusdeal.agent.Tool;
import com.campusdeal.entity.Merchant;
import com.campusdeal.service.IMerchantService;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 按关键字搜索商家。
 */
@Component
public class SearchMerchantTool {

    @Resource
    private IMerchantService merchantService;

    @Tool(name = "search_merchant", description = "按名称关键字模糊搜索平台商家，最多返回 10 条，含 id、名称、均价")
    public String searchMerchant(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return "{\"error\":\"keyword 不能为空\"}";
        }
        List<Merchant> merchants = merchantService.list(
                new QueryWrapper<Merchant>().like("name", keyword).last("limit 10"));
        if (merchants == null || merchants.isEmpty()) {
            return "{\"merchants\":[]}";
        }
        String items = merchants.stream()
                .map(m -> String.format("{\"id\":%d,\"name\":\"%s\",\"avgPrice\":%d}",
                        m.getId(), m.getName(), m.getAvgPrice() == null ? 0 : m.getAvgPrice()))
                .collect(Collectors.joining(","));
        return "{\"merchants\":[" + items + "]}";
    }
}
