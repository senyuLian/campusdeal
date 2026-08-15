package com.campusdeal.agent.tool;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.campusdeal.agent.Tool;
import com.campusdeal.entity.Merchant;
import com.campusdeal.service.IMerchantService;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.List;

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
        // T11：String.format 手拼 JSON 在名称含引号/反斜杠时产生非法 JSON，改用 JSON 对象序列化
        cn.hutool.json.JSONArray arr = cn.hutool.json.JSONUtil.createArray();
        for (Merchant m : merchants) {
            arr.add(cn.hutool.json.JSONUtil.createObj()
                    .set("id", m.getId())
                    .set("name", m.getName())
                    .set("avgPrice", m.getAvgPrice() == null ? 0 : m.getAvgPrice()));
        }
        return cn.hutool.json.JSONUtil.createObj().set("merchants", arr).toString();
    }
}
