# 01 · 接口测试

> 覆盖全部 REST 接口的请求/响应契约、鉴权矩阵、五维用例设计与 curl 示例。
> 统一入口：后端 `http://localhost:8081`，nginx 反代前缀 `/api`。

## 1. 统一契约

### 1.1 响应结构 `Result`

```json
{ "success": true, "errorMsg": null, "data": {}, "total": null }
```

| 字段 | 类型 | 说明 |
|---|---|---|
| success | boolean | true=成功 / false=失败 |
| errorMsg | string | 失败原因（成功为 null） |
| data | object/array/string | 业务数据 |
| total | long | 分页总数（`Result.ok(list, total)`） |

序列化规则：Jackson `default-property-inclusion: non_null`，**null 字段不输出**。

### 1.2 鉴权规则

| 拦截器 | order | 职责 |
|---|---|---|
| `RefreshTokenInterceptor` | 0（全路径） | 读 `authorization` 头 → Redis Hash `login:token:{token}` → 写 `UserHolder`，滑动续期；**开头强制 `removeUser()`**（P0-5） |
| `LoginInterceptor` | 2（受保护路径） | `UserHolder.getUser()==null` → **HTTP 401** |

公开路径（免登录）：
`/user/code`、`/user/login`、`/post/hot`、`/post/*`、`/post/likes/*`、`/post/of/user`、`/user/public/*`、`/merchant/**`、`/merchant-type/**`、`/upload/**`、`/coupon/**`、`/chat.html`、`/css/**`、`/js/**`

> ⚠️ `/merchant` 的 **POST/PUT 写接口** 虽在公开路径，但 Controller 内自校验登录（无 token → 401，P0-4）。`/coupon` 的写接口同理暴露但未做自校验，见 §8 风险。

### 1.3 通用错误兜底

`WebExceptionAdvice`：所有 `RuntimeException` → `Result.fail("服务器异常")`（HTTP 200 + success=false），日志 error。

## 2. 接口总览矩阵

> 鉴权列：🟢=公开 / 🔒=需登录 / ⚠️=公开路径但写接口自校验

### 用户 `/user`

| # | 方法 | 路径 | 参数 | 鉴权 | 成功 data |
|---|---|---|---|---|---|
| U1 | POST | `/user/code` | query: phone | 🟢 | "Verification code sent" |
| U2 | POST | `/user/login` | body: {phone, code/phone, password} | 🟢 | token（String） |
| U3 | POST | `/user/logout` | header: authorization | 🔒 | null |
| U4 | GET | `/user/me` | - | 🔒 | UserDTO |
| U5 | GET | `/user/info/{id}` | path: id | 🔒 | UserInfo |
| U6 | GET | `/user/{id}` | path: id | 🔒 | UserDTO |
| U7 | GET | `/user/public/{id}` | path: id | 🟢 | UserDTO |
| U8 | POST | `/user/sign` | - | 🔒 | null |
| U9 | GET | `/user/sign/count` | - | 🔒 | 连续签到天数(int) |

### 商户 `/merchant`

| # | 方法 | 路径 | 参数 | 鉴权 | 说明 |
|---|---|---|---|---|---|
| M1 | GET | `/merchant/{id}` | path: id | 🟢 | 逻辑过期缓存；不存在→fail"店铺不存在" |
| M2 | POST | `/merchant` | body: Merchant | ⚠️ | 返回新 id |
| M3 | PUT | `/merchant` | body: Merchant(含id) | ⚠️ | 更新并删缓存 |
| M4 | GET | `/merchant/of/type` | typeId, current, x?, y? | 🟢 | GEO 或 DB 分页 |
| M5 | GET | `/merchant/nearby` | x?, y?, current | 🟢 | 内存距离排序 |
| M6 | GET | `/merchant/of/name` | name?, current | 🟢 | LIKE 分页 |

### 商户类型 `/merchant-type`

| # | 方法 | 路径 | 参数 | 鉴权 | 说明 |
|---|---|---|---|---|---|
| MT1 | GET | `/merchant-type/list` | - | 🟢 | 全部分类 |

### 优惠券 `/coupon`

| # | 方法 | 路径 | 参数 | 鉴权 | 说明 |
|---|---|---|---|---|---|
| C1 | POST | `/coupon` | body: Coupon | 🟢 | 新增普通券，返回 id |
| C2 | POST | `/coupon/seckill` | body: Coupon(含stock/beginTime/endTime) | 🟢 | 券+FlashDeal+Redis 库存 |
| C3 | GET | `/coupon/list/{shopId}` | path: shopId | 🟢 | 店铺券列表 |
| C4 | GET | `/coupon/flash/list` | - | 🟢 | 进行中闪购券 |
| C5 | GET | `/coupon/list/all` | - | 🟢 | 全站上架券 |

### 秒杀订单 `/coupon-order`

| # | 方法 | 路径 | 参数 | 鉴权 | 说明 |
|---|---|---|---|---|---|
| CO1 | POST | `/coupon-order/seckill/{id}` | path: dealId | 🔒 | 三层过滤；成功返回 **orderId 字符串** |

### 帖子 `/post`

| # | 方法 | 路径 | 参数 | 鉴权 | 成功 data |
|---|---|---|---|---|---|
| P1 | POST | `/post` | body: Post | 🔒 | post id |
| P2 | PUT | `/post/like/{id}` | path: id | 🔒 | null |
| P3 | GET | `/post/of/me` | current | 🔒 | 我的帖子 |
| P4 | GET | `/post/of/user` | current, id | 🟢 | 指定用户帖子 |
| P5 | GET | `/post/hot` | current | 🟢 | 按点赞倒序 |
| P6 | GET | `/post/{id}` | path: id | 🟢 | 详情(含 isLike) |
| P7 | GET | `/post/likes/{id}` | path: id | 🟢 | 点赞 Top5 UserDTO |
| P8 | GET | `/post/of/follow` | lastId, offset | 🔒 | ScrollResult |

### 关注 `/follow`

| # | 方法 | 路径 | 参数 | 鉴权 | 说明 |
|---|---|---|---|---|---|
| F1 | PUT | `/follow/{id}/{isFollow}` | path: id, isFollow | 🔒 | 关注/取关 |
| F2 | GET | `/follow/or/not/{id}` | path: id | 🔒 | 是否关注 |
| F3 | GET | `/follow/common/{id}` | path: id | 🔒 | 共同关注 |

### 上传 `/upload`

| # | 方法 | 路径 | 参数 | 鉴权 | 说明 |
|---|---|---|---|---|---|
| Up1 | POST | `/upload/post` | multipart: file | 🟢 | 返回相对路径 |
| Up2 | POST | `/upload/delete` | query: name | 🟢 | 删除（P1-6 改 POST） |
| Up3 | GET | `/upload/post/delete` | query: name | 🟢 | 旧 GET 兼容 |

### Agent `/agent`（需登录）

| # | 方法 | 路径 | 参数 | 鉴权 | 说明 |
|---|---|---|---|---|---|
| A1 | POST | `/agent/chat` | form: message, sessionId? | 🔒 | SSE 流 |
| A2 | GET | `/agent/history/{sessionId}` | path: sessionId | 🔒 | 会话历史 |
| A3 | POST | `/agent/confirm` | body: {confirmationId, approved} | 🔒 | 敏感确认 |

## 3. 五维用例设计模板

每个接口按下表生成用例（预计 36 接口 × 4~6 用例 ≈ 180+）：

| 维度 | 输入 | 期望 |
|---|---|---|
| 参数合法 | 完整合法入参 | 200 + success=true + data 契约 |
| 参数缺失/非法 | 缺参、非法格式、超长 | 200 + success=false（或全局兜底），不 500 |
| 未登录 | 受保护接口无 token | **HTTP 401** |
| 已登录 | 带合法 token | 200 + success=true |
| 数据边界 | 不存在 id / 空列表 / 页码越界 | 优雅 fail 或空数组 |

## 4. 用户模块用例

### U1 发送验证码 `POST /user/code?phone=`

```bash
curl -s -X POST "http://localhost:8081/user/code?phone=13800001111"
# → {"success":true,"data":"Verification code sent",...}
```

| ID | 用例 | 预期 |
|---|---|---|
| U1-01 | 合法手机号 | success=true；Redis `login:code:{phone}` 有 6 位码，TTL=2min |
| U1-02 | 非法手机号 `12345` / `abc` / 空 | fail"Invalid phone number"；不写 Redis |
| U1-03 | 重复发送 | 覆盖旧码，TTL 重置 |

### U2 登录 `POST /user/login`

```json
{ "phone": "13800001111", "code": "xxxxxx" }
```

| ID | 用例 | 预期 |
|---|---|---|
| U2-01 | 验证码正确 | success=true，data=token；Redis `login:token:{token}` Hash 含 id/nickName/icon，TTL=36000s |
| U2-02 | 验证码错误 | fail"Invalid verification code" |
| U2-03 | 验证码过期（Redis 无 key） | fail"Invalid verification code"（`redisCode==null`） |
| U2-04 | 非法手机号 | fail"Invalid phone number" |
| U2-05 | 新手机号首登 | 自动注册用户，昵称 `user_`+随机10位 |
| U2-06 | 已注册用户登录 | 复用原用户，不重复创建 |
| U2-07 | 同一手机号并发登录 | 各自返回不同 token，均可通过鉴权 |

### U3 登出 `POST /user/logout`

| ID | 用例 | 预期 |
|---|---|---|
| U3-01 | 带有效 token | success=true；Redis token 被删除 |
| U3-02 | 登出后旧 token 访问 `/user/me` | **401** |
| U3-03 | 无 token 调 logout | 500（`@RequestHeader` 必需，全局兜底）——已知行为 |

### U4~U6 用户信息

| ID | 用例 | 预期 |
|---|---|---|
| U4-01 | 登录后 `/user/me` | 返回当前用户 UserDTO |
| U4-02 | 未登录 `/user/me` | 401 |
| U5-01 | 有详情 `/user/info/{id}` | 返回 UserInfo（createTime/updateTime 置 null） |
| U5-02 | 无详情 `/user/info/{id}` | success=true data=null（首次查看） |
| U6-01 | 用户存在 `/user/{id}` | 返回 UserDTO |
| U6-02 | 用户不存在 | success=true data=null |

### U7 公开主页 `GET /user/public/{id}`

| ID | 用例 | 预期 |
|---|---|---|
| U7-01 | 存在 | 返回 UserDTO（无需登录） |
| U7-02 | 不存在 | fail"用户不存在" |

### U8/U9 签到

| ID | 用例 | 预期 |
|---|---|---|
| U8-01 | 首次签到 | success=true；BitMap `sign:{userId}:{year}:{month}` 第 day-1 位为 1 |
| U8-02 | 同天重复签到 | success=true（幂等），天数不变 |
| U8-03 | 未登录签到 | 401 |
| U9-01 | 连续签到 N 天 | 返回 N |
| U9-02 | 断签后 | 返回从今天起向前的连续天数 |
| U9-03 | 未签到 | 返回 0 |

## 5. 商户模块用例

### M1 详情 `GET /merchant/{id}`

| ID | 用例 | 预期 |
|---|---|---|
| M1-01 | 命中缓存 | 200，数据正确 |
| M1-02 | 缓存未命中（未预热） | 回源 DB 并重建逻辑过期缓存（`RedisData` 包装） |
| M1-03 | 缓存已过期 | 后台线程重建，返回旧数据（击穿防御） |
| M1-04 | 不存在 id（如 99999） | fail"店铺不存在" |
| M1-05 | 缓存空值（穿透防御） | 命中空值 `""` 快速返回 |

### M2/M3 写接口（P0-4 回归重点）

```bash
curl -s -X POST http://localhost:8081/merchant -H "Content-Type: application/json" \
  -d '{"name":"测试店","typeId":1,"images":"","address":"x","x":120,"y":30}'
# 无 token → HTTP 401
```

| ID | 用例 | 预期 |
|---|---|---|
| M2-01 | 无 token 新增 | **401** |
| M2-02 | 有 token 新增 | 200 + 新 id |
| M3-01 | 无 token 更新 | **401** |
| M3-02 | 有 token 更新 | 200；`cache:merchant:{id}` 被删除 |
| M3-03 | 更新 id 为 null | fail"店铺id不能为空" |

### M4 按类型分页 `GET /merchant/of/type?typeId=1&current=1`

| ID | 用例 | 预期 |
|---|---|---|
| M4-01 | 无坐标 | DB 分页（DEFAULT_PAGE_SIZE=5） |
| M4-02 | 有坐标且 GEO 已预热 | 5000m 半径，GEO 结果含 distance |
| M4-03 | GEO 未预热（`merchant:geo:{typeId}` 无数据） | 降级回源 DB，不白屏 |
| M4-04 | typeId 无商户 | 空数组 |
| M4-05 | current 越界 | 空数组 |

### M5 附近 `GET /merchant/nearby?x=120.15&y=30.31`

| ID | 用例 | 预期 |
|---|---|---|
| M5-01 | 带坐标 | 按距离升序，distance 字段存在 |
| M5-02 | 无坐标 | 默认顺序返回 |
| M5-03 | 无商户 | 空数组 |

### M6 关键字 `GET /merchant/of/name?name=茶`

| ID | 用例 | 预期 |
|---|---|---|
| M6-01 | 有关键字 | LIKE 命中，MAX_PAGE_SIZE=10 |
| M6-02 | 空关键字 | 返回全部（分页） |
| M6-03 | 无命中 | 空数组 |

### MT1 类型列表

| ID | 用例 | 预期 |
|---|---|---|
| MT1-01 | 正常 | 全部分类含 id/name/icon/sort，按 sort 排序 |

## 6. 优惠券与秒杀用例

### C1 新增普通券

```json
{ "shopId": 1, "title": "10元代金券", "type": 1, "status": 1, "payValue": 1, "actualValue": 10 }
```

| ID | 用例 | 预期 |
|---|---|---|
| C1-01 | 合法 | 返回券 id |
| C1-02 | 缺字段 | 200（数据库兜底）或全局兜底，不 500 |

### C2 新增闪购券

| ID | 用例 | 预期 |
|---|---|---|
| C2-01 | 合法（含 stock/begin/end） | 券 + `tb_seckill_voucher` + Redis `flashdeal:stock:{id}` 三处一致 |
| C2-02 | stock=0 | Redis 库存 0，秒杀直接 Out of stock |

### C3~C5 查询

| ID | 用例 | 预期 |
|---|---|---|
| C3-01 | 店铺有券 | 返回列表（XML 联表：普通券+秒杀券） |
| C3-02 | 店铺无券 | 空数组 |
| C4-01 | 有进行中闪购 | status=1、库存>0、时间窗内 |
| C4-02 | 无进行中 | 空数组 |
| C5-01 | 全站 | 普通+闪购混合，含商户名/图片 |

### CO1 秒杀 `POST /coupon-order/seckill/{dealId}`

```bash
# 需登录
curl -s -X POST http://localhost:8081/coupon-order/seckill/11 -H "authorization: <token>"
# 成功 → {"success":true,"data":"76413360890970116"}   ← 字符串，防 JS 精度
```

| ID | 用例 | 预期 |
|---|---|---|
| CO1-01 | 库存充足 | 返回 orderId **字符串**；库存-1；`tb_voucher_order` 落库 |
| CO1-02 | 库存不足 | fail"Out of stock"；L1 `stockCache` 回填 false |
| CO1-03 | 同用户重复 | fail"Already purchased"（Lua 返回 2） |
| CO1-04 | 未登录 | **401** |
| CO1-05 | 活动未开始 | fail"Flash deal has not started"（旧链路） |
| CO1-06 | 活动已结束 | fail"Flash deal has ended" |
| CO1-07 | dealId 不存在（布隆拒绝） | fail"Deal not found" |
| CO1-08 | Kafka 未运行时秒杀 | **不阻塞**（异步发送），响应 <100ms |

> 注：CO1 走 `FlashDealServiceImpl.executeFlashDeal`（三层过滤）。CouponOrderServiceImpl 的 `seckillVoucher`（Redisson 锁版本）仍存在但非当前主链路。

## 7. 帖子 / 关注 / 上传用例

### P1 发布 `POST /post`

| ID | 用例 | 预期 |
|---|---|---|
| P1-01 | 登录发帖 | 返回 post id；粉丝 `feed:{userId}` ZSet 收到 |
| P1-02 | 未登录 | 401 |
| P1-03 | 无粉丝 | 发帖成功，无 Feed 推送 |

### P2 点赞 `PUT /post/like/{id}`

| ID | 用例 | 预期 |
|---|---|---|
| P2-01 | 首次点赞 | liked+1；`post:liked:{id}` ZSet 加入 userId |
| P2-02 | 再次点赞（取消） | liked-1；ZSet 移除 userId |
| P2-03 | 帖子不存在 | 点赞计数为负/空，ZSet 空（当前无校验——已知行为） |

### P3~P8 查询

| ID | 用例 | 预期 |
|---|---|---|
| P3-01 | 登录 `/post/of/me` | 本人帖子 |
| P3-02 | 未登录 | 401 |
| P4-01 | 指定用户 | 该用户帖子分页 |
| P5-01 | `/post/hot` | liked 倒序 |
| P6-01 | 帖子存在 | 详情含 name/icon/isLike |
| P6-02 | 帖子不存在 | fail"笔记不存在！" |
| P7-01 | 有赞 | Top5 点赞用户 |
| P7-02 | 无赞 | 空列表 |
| P8-01 | Feed 滚动 | ScrollResult{list,minTime,offset} |
| P8-02 | lastId/offset 翻页 | 无重复无遗漏（同 score 用 offset 兜底） |

### F1~F3 关注

| ID | 用例 | 预期 |
|---|---|---|
| F1-01 | 关注 | DB + Redis Set `follows:{userId}` 双写 |
| F1-02 | 取关 | 双删 |
| F2-01 | 已关注 | true |
| F2-02 | 未关注 | false |
| F3-01 | 有共同关注 | 返回交集用户 |
| F3-02 | 无共同 | 空数组 |

### Up1~Up3 上传

| ID | 用例 | 预期 |
|---|---|---|
| Up1-01 | 上传图片 | 返回 `/blogs/{d1}/{d2}/{uuid}.{ext}`；文件落盘 `IMAGE_UPLOAD_DIR` |
| Up1-02 | 非图片文件 | 全局兜底（MultipartException） |
| Up2-01 | 删除文件 | success=true（POST，P1-6） |
| Up2-02 | 删除目录名 | fail"错误的文件名称" |
| Up3-01 | 旧 GET 删除 | 兼容可用 |

## 8. Agent 接口用例

| ID | 用例 | 预期 |
|---|---|---|
| A1-01 | 无 token `/agent/chat` | **401** |
| A1-02 | 有 token，正常问答 | 200 + `Content-Type: text/event-stream`，事件流以 done 收尾 |
| A1-03 | 空 message | 清洗拒绝 → error 事件 |
| A1-04 | 超限（同用户 10 次/min） | error 事件"请求过于频繁" |
| A1-05 | 无 DeepSeek Key | thinking → error 收尾，不挂起 |
| A2-01 | 有历史 | 返回消息列表 |
| A2-02 | 无历史/不存在 sessionId | 空会话 |
| A3-01 | confirmationId 有效+approved | 执行工具，success=true |
| A3-02 | approved=false | success=false，操作取消 |
| A3-03 | confirmationId 不存在/过期 | success=false"确认已过期或不存在" |

> SSE 事件流协议详见 [02-integration-e2e.md](./02-integration-e2e.md#sse-事件流协议)。

## 9. 契约测试

| ID | 检查项 | 断言 |
|---|---|---|
| CT-01 | JSON 序列化 | 所有 null 字段不出现（non_null） |
| CT-02 | orderId 精度 | data 为字符串，`JSON.parse` 不丢尾位 |
| CT-03 | 分页 total | 只有 `Result.ok(list,total)` 时非 null |
| CT-04 | 错误响应 | 一律 HTTP 200 + success=false（401 例外为 HTTP 401） |
| CT-05 | 全局异常 | RuntimeException → "服务器异常"，不泄漏堆栈到响应 |

## 10. 风险与待确认

| # | 风险 | 说明 |
|---|---|---|
| R1 | `/coupon` POST 写接口未自校验登录 | 公开路径可匿名建券；与 P0-4 修法不一致，需产品确认或补校验 |
| R2 | U3-03 无 token 登出返回 500 | `@RequestHeader` 必需，依赖全局兜底；前端需保证传 token |
| R3 | P2-03 帖子不存在点赞 | 无存在性校验，liked 可成负数；需决定是否加固 |
| R4 | 过期单测 | `FlashDealProducerTest`/`FlashDealServiceImplTest` 与现实现脱节，先修再跑（见 11 文档） |
