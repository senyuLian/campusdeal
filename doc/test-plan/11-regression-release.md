# 11 · 回归与发布

> 分层回归策略、已知问题回归矩阵、发布门禁、发布清单与回滚预案。
> 全部历史缺陷（P0/P1/P2）与本轮新增已知问题（T1–T10）的回归项汇总。

## 1. 回归策略（金字塔）

```
层级                   范围                            执行时机          责任人
─────────────────────────────────────────────────────────────────────────────
① 单测 (JUnit5)       全部模块单测                    每次构建          开发
② 接口契约            curl 脚本 01-接口契约            每轮联调前        测试
③ E2E 场景            S1–S5 全流程                    每轮回归          测试
④ 性能冒烟            秒杀/Agent 最小压测              发布前            测试
⑤ 全量回归            ①②③④ + 已知问题矩阵           发布候选          测试/开发
```

执行脚本：
- ① `mvn test`（JDK17）
- ③ `tools/` 下 E2E 脚本（见 02 文档 §4 CDP 脚本化回归）
- ② 01 文档 §契约测试

## 2. 历史缺陷回归矩阵（P0/P1/P2）

| 编号 | 缺陷 | 修复方式 | 回归验证 |
|---|---|---|---|
| P0-1 | Kafka 阻塞主链路 | producer 异步化 + max.block.ms=3000 | 停 Kafka 秒杀不阻塞、响应<100ms |
| P0-2 | 秒杀订单不落库 | 同步落库 + Outbox 补偿 | 秒杀后查 DB 订单存在；模拟 DB 异常有 PENDING |
| P0-3 | 修改商户接口公开 | MvcConfig 收紧 | 匿名 POST/PUT /merchant → 401 |
| P0-4 | 帖子/商户写接口公开 | 同上 | 匿名写 → 401 |
| P0-5 | SSE ThreadLocal 越权泄漏 | `auth-leak-test` | 40 并发匿名 → 全部 401 |
| P1-6 | 商户搜索未用缓存 | CacheClient + 逻辑过期 | 商户列表二次请求 DB 零查询 |
| P1-7 | Redis 库存不恢复 | 启动预热 setIfAbsent | 清 Redis 重启后库存恢复 |
| P1-8 | 订单号 JS 精度 | RedisIdWorker 返回 String | 前端订单号显示完整、可追溯 |
| P2-6 | 分页/滚动 | ScrollResult 游标 | 帖子/商户滚动分页无重不漏 |

## 3. 已知问题回归矩阵（T1–T10）

> ⚠️ 以下问题在本轮"测试方案"评审中发现，**需先修复或确认口径再进入发布门禁**。
> 修复后各文档对应用例须回归。

| 编号 | 问题 | 影响 | 所在文档 | 修复后回归项 |
|---|---|---|---|---|
| T1 | `FlashDealProducerTest` 断言旧同步语义 | 单测红 | 06 §3 | KP-01/02 |
| T2 | `FlashDealServiceImplTest.FD-03` 未 Mock Consumer/Outbox | 单测 NPE | 05 §4.1 | FD-03 |
| T3 | `confirm-tools:[applyRefund]` 与工具名 `apply_refund` 不匹配 | **退款二次确认被绕过** | 07 §5 / 09 §4 | SG-02/07, CONF-06 |
| T4 | `tb_voucher_order` 无 `(user_id, voucher_id)` 唯一索引 | 幂等窗口外可能重复单 | 06 §4 | KC-02 补 DB 层断言 |
| T5 | 部分 Agent 模块无单测（工具/SSE 协议） | 回归盲区 | 07 §3/§4 | TOOL-01~11, SSE 协议断言 |
| T6 | `rate-limit-per-minute` 未接线（硬编码 10） | 限流不可调 | 09 §5 | RL-06 |
| T7 | Canal 无自动重连 | 缓存失效中断 | 06 §7.2 | CN-07 重连用例 |
| T8 | 敏感确认上下文在 JVM 内存、无超时、不校验 userId | 分布式不可靠 | 09 §4 | SG-05 补超时/归属 |
| T9 | `pii-mode` REMOVE/PASS 未实现、`injection-sensitivity` 未接线 | 配置不生效 | 09 §2 | IS-08/注入阈值用例 |
| T10 | RAG 工具零测试 | 回归盲区 | 08 §8 | SF-01/02, SG-01/02 |

**优先级**：P0 类 = T3（安全绕过后门）> T1/T2（CI 红）> T4/T6/T7（一致性/配置）> T5/T8/T9/T10（加固/覆盖）。

## 4. 发布门禁（全部满足才可发布）

```
[ ] mvn test 全绿（JDK17；T1/T2 修复后）
[ ] 接口契约 36+ 全通过（01 文档）
[ ] E2E S1–S5 通过（02 文档）
[ ] 秒杀并发：30/10 不超卖、对账一致（05 §5 CC-01/03）
[ ] 安全：P0-3/4/5 回归通过；T3 已修复且 confirm 生效（09）
[ ] 性能冒烟：秒杀 P99<150ms、Agent TTFT<2s（10 文档）
[ ] 无 Kafka/Canal/DeepSeek Key 环境降级可用（06 §8 / 08 §9）
[ ] 已知问题 T1–T10 全部闭环（修复 or 产品确认接受并记录）
```

## 5. 发布清单

### 5.1 前置确认

```markdown
- [ ] 环境：MySQL/Redis 可用，campusdeal.sql 已初始化
- [ ] 配置：application.yaml 环境差异（DB/Redis 密码、Kafka/Canal/DeepSeek 开关）
- [ ] 前端：nginx:8080 反代 /api → 后端 8081；token key `campusdeal-token`
- [ ] 图片目录：`SystemConstants.IMAGE_UPLOAD_DIR` 存在且可写
```

### 5.2 构建与启动

```bash
export JAVA_HOME="D:/program/develop/jdks/jdk17"
mvn clean package -DskipTests
java -jar target/*.jar --spring.profiles.active=prod  # 或对应环境
```

### 5.3 冒烟清单（发布后 5 分钟）

```markdown
- [ ] 健康检查 /actuator/health 返回 UP
- [ ] 首页 / 商户列表可访问（nginx → 后端）
- [ ] 登录（/user/code + /user/login）→ token 生效
- [ ] 任一秒杀活动可正常抢购并落库
- [ ] Agent 会话可发起并收到 SSE 事件流
- [ ] 日志无 ERROR 刷屏、无明文 PII
```

## 6. 回滚预案

| 场景 | 动作 | 影响 |
|---|---|---|
| 秒杀/订单异常 | 回退上一版本 + 停发秒杀活动 | 已下订单保留（Outbox 补偿可继续） |
| Agent 异常 | 回退 + `agent.enabled=false`（若存在开关） | 其余业务不受影响 |
| 数据不一致 | 对账脚本核对订单/库存 | 差异订单人工处理 |
| 缓存污染 | 清 Redis `cache:*`/`flashdeal:*`（**不清登录 token**） | 冷启动预热恢复 |
| 前端异常 | 回退 nginx 静态目录 | 即时生效 |

## 7. 回归结果记录

每次发布候选填写：

```markdown
| 日期 | 范围 | 结果 | 失败项 | 备注 |
|---|---|---|---|---|
| 2026-08-15 | ①②③ | PASS | - | T3 已修复 |
| 2026-08-15 | ④⑤ | PASS | - | 性能达标 |
```

## 8. 验收标准

- 发布门禁全部勾选，T1–T10 闭环。
- 全量回归无 P0/P1 缺陷；P2 可记录已知并接受。
- 回滚预案就绪，相关人员知晓执行方式。
