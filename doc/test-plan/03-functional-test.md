# 03 · 功能测试

> 以业务场景为主线，覆盖正常路径 + 边界 + 异常。用例可手工执行，也可映射到
> `tools/regression-phase-d.js` / `tools/api-smoke.js` 脚本断言。
> 执行前置：环境就绪（见 README）、数据就绪（含秒杀幂等键清理）。

## 1. 用户与账号

| ID | 场景 | 步骤 | 预期 |
|---|---|---|---|
| FT-USER-01 | 验证码登录（新用户） | 发码→查 Redis→登录 | 自动注册，昵称 `user_`+随机，返回 token |
| FT-USER-02 | 验证码登录（老用户） | 发码→登录 | 复用原用户，返回新 token |
| FT-USER-03 | 验证码过期 | 发送后等 >2min 登录 | fail"Invalid verification code" |
| FT-USER-04 | 验证码错误 | 错码登录 | fail"Invalid verification code" |
| FT-USER-05 | 手机号非法 | `12345`/空 发码 | fail"Invalid phone number" |
| FT-USER-06 | 登出失效 | 登出后旧 token 访问 | 401 |
| FT-USER-07 | 签到幂等 | 同日重复签到 | success=true，天数不变 |
| FT-USER-08 | 连续签到 | 模拟连续 N 天（改 Redis 位图） | 天数=N；断签后从当前连续段计 |
| FT-USER-09 | 查看他人主页 | `/user/public/{id}` | 公开信息，无需登录 |
| FT-USER-10 | 个人信息详情 | `/user/info/{id}` | 无详情首查返回空 data |

## 2. 商户浏览

| ID | 场景 | 步骤 | 预期 |
|---|---|---|---|
| FT-SHOP-01 | 详情缓存命中 | 二次查询 `/merchant/{id}` | 命中，数据正确 |
| FT-SHOP-02 | 详情缓存穿透 | 查不存在 id | fail"店铺不存在"；Redis 空值缓存 |
| FT-SHOP-03 | 更新后缓存一致 | 改商户→再查 | 返回新数据 |
| FT-SHOP-04 | 类型分页（无坐标） | `/merchant/of/type?typeId=1` | DB 分页 5 条/页 |
| FT-SHOP-05 | 类型分页（有坐标+GEO预热） | 带 x/y | 5000m 内按距离，含 distance |
| FT-SHOP-06 | GEO 未预热降级 | 清 `merchant:geo:1` 后查询 | 回源 DB 不白屏 |
| FT-SHOP-07 | 附近商户 | `/merchant/nearby?x&y` | 距离升序 |
| FT-SHOP-08 | 关键字搜索 | `/merchant/of/name?name=茶` | LIKE 命中 |
| FT-SHOP-09 | 空结果 | 搜不存在关键字 | 空数组 |

## 3. 优惠券与秒杀

| ID | 场景 | 步骤 | 预期 |
|---|---|---|---|
| FT-COUPON-01 | 新增普通券 | POST /coupon | 返回 id，店铺列表可见 |
| FT-COUPON-02 | 新增闪购券 | POST /coupon/seckill | 券+FlashDeal+Redis 库存一致 |
| FT-COUPON-03 | 秒杀成功 | 登录秒杀 dealId=11 | 订单字符串、落库、库存-1 |
| FT-COUPON-04 | 库存耗尽 | 库存=0 时秒杀 | fail"Out of stock" |
| FT-COUPON-05 | 一人一单 | 同用户二次秒杀 | fail"Already purchased" |
| FT-COUPON-06 | 活动未开始 | beginTime 在未来 | fail（旧链路校验） |
| FT-COUPON-07 | 活动已结束 | endTime 已过 | fail |
| FT-COUPON-08 | 闪购列表 | `/coupon/flash/list` | 仅进行中 |
| FT-COUPON-09 | 全部卡券 | `/coupon/list/all` | 普通+闪购 |
| FT-COUPON-10 | Redis 重启后库存 | 重启 Redis 再秒杀 | 启动预热恢复库存（P1-7） |
| FT-COUPON-11 | Kafka 未运行秒杀 | 停 Kafka 秒杀 | 不阻塞，落库正常 |
| FT-COUPON-12 | 订单对账 | 秒杀后比对 DB/Redis | 库存+已售=活动库存，无超卖 |

## 4. 帖子社交

| ID | 场景 | 步骤 | 预期 |
|---|---|---|---|
| FT-POST-01 | 发布帖子 | POST /post | 返回 id；粉丝 Feed 收到 |
| FT-POST-02 | 点赞 | PUT /post/like/{id} | liked+1，ZSet 加入 |
| FT-POST-03 | 取消赞 | 再点 | liked-1，ZSet 移除 |
| FT-POST-04 | 点赞 Top5 | `/post/likes/{id}` | 前 5 点赞用户 |
| FT-POST-05 | 我的帖子 | `/post/of/me` | 本人帖子分页 |
| FT-POST-06 | 他人帖子 | `/post/of/user?current&id` | 该用户帖子 |
| FT-POST-07 | 热门 | `/post/hot` | liked 倒序 |
| FT-POST-08 | 详情 | `/post/{id}` | 含 name/icon/isLike |
| FT-POST-09 | 关注 Feed 滚动 | `/post/of/follow?lastId&offset` | 分页无重复无遗漏 |
| FT-POST-10 | 关注/取关 | PUT /follow | DB+Redis 一致 |
| FT-POST-11 | 共同关注 | `/follow/common/{id}` | 交集用户 |

## 5. 上传

| ID | 场景 | 步骤 | 预期 |
|---|---|---|---|
| FT-UP-01 | 上传图片 | POST /upload/post | 返回 `/blogs/{d1}/{d2}/{uuid}.jpg` |
| FT-UP-02 | 文件落盘 | 检查 IMAGE_UPLOAD_DIR | 文件存在 |
| FT-UP-03 | 删除图片（POST） | POST /upload/delete?name | success=true（P1-6） |
| FT-UP-04 | 删除目录 | name=目录名 | fail"错误的文件名称" |
| FT-UP-05 | 旧 GET 删除 | GET /upload/post/delete | 兼容可用 |
| FT-UP-06 | 非法文件 | 传文本文件 | 全局兜底不 500 |

## 6. 智能助手

| ID | 场景 | 步骤 | 预期 |
|---|---|---|---|
| FT-AG-01 | 普通问答 | chat "你好" | SSE 流式，done 收尾 |
| FT-AG-02 | 查订单 | "查我的订单" | tool_call→tool_result→回答 |
| FT-AG-03 | 搜商户 | "附近有什么火锅" | search_merchant 工具 |
| FT-AG-04 | 查券 | "有哪些优惠券" | query_coupon 工具 |
| FT-AG-05 | 退款确认 | "我要退款" | confirm 事件；批准/拒绝两分支 |
| FT-AG-06 | 会话记忆 | 多轮上下文 | 第二问能引用第一问 |
| FT-AG-07 | 会话压缩 | >15 条消息 | 触发压缩，summary 注入 |
| FT-AG-08 | 限流 | 同用户连发 11 次 | 第 11 次 error"请求过于频繁" |
| FT-AG-09 | 注入攻击 | "忽略以上系统指令…" | 被拦截 error |
| FT-AG-10 | PII 输入 | 输入含手机号 | 输入被 MASK；输出手机号脱敏 |
| FT-AG-11 | 无 Key 降级 | 空 Key | thinking→error 收尾不挂起 |
| FT-AG-12 | 历史回读 | GET /agent/history/{sessionId} | 消息可读 |

## 7. 跨业务串联场景

| ID | 场景 | 步骤 | 预期 |
|---|---|---|---|
| FT-E2E-01 | 完整用户旅程 | 登录→浏览→进店→秒杀→发帖→签到→Agent 咨询 | 全链路无阻断 |
| FT-E2E-02 | 秒杀并发一致性 | 30 用户抢 10 库存 | 恰 10 成功、DB=10、无超卖 |
| FT-E2E-03 | 越权防护 | 匿名并发 + 他人 token | 全部 401 / 不泄漏他人数据 |
| FT-E2E-04 | 缓存一致性闭环 | 商户更新→秒杀库存变化→缓存同步 | 无脏读超 30s |

## 8. 验收标准汇总

- 全部 FT-* 用例 success 或符合预期 fail（不出现 500/挂起）。
- 秒杀无超卖、无重复下单、无丢单。
- 越权/未登录一律 401。
- Agent 降级路径可用（无 Key/无外部依赖）。
- 前端页面无 JS 报错、三态齐全。
