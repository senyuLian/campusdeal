**CampusDeal 项目审查与改进建议 — 2026-09-19**

审查基线：Git `1e4a47f`。本次检查了 README、构建与配置、数据库结构、主要控制器、业务服务、Redis 缓存与秒杀、Kafka/Outbox/Canal、Agent/RAG、安全模块及相关测试。没有修改业务代码。

项目已经具备模块化单体的主要骨架：商户与社交业务使用 MyBatis-Plus/MySQL；Redis 承担登录态、缓存、社交集合和秒杀准入；Kafka 消费者异步写订单，Outbox 承担失败补偿；LangGraph4j 编排客服工具，Redis 保存会话，BM25 与可选向量检索提供知识检索。目前最值得投入的是权限边界、缓存正确性和订单故障恢复。

**验证结果与边界**

- 使用项目要求的 JDK 17 执行 `mvn -B test`：**173 项通过，0 失败、0 错误、0 跳过**。README 的 174 项与本次实际执行数不同。
- 额外完成 **10 项隔离检查**：跨用户会话读取、缓存锁冲突、补偿误报完成、输出脱敏晚于 SSE 发送、上传删除路径越界、RAG 尾部分块、PostgreSQL 驱动缺失、匿名优惠券写入、普通用户修改商户、Outbox 创建时间为空。
- MVC 检查使用真实控制器和 MvcConfig，下游业务服务为 Mock。其余检查使用实际业务方法、模拟依赖或路径解析；未连接现有数据库、Redis、Kafka、LLM，也未执行文件删除。
- 没有执行 `mvn verify`、真实服务故障注入或压测。下面的“代码推演”不能当作已完成生产环境复现；本地数据库是否存在额外手工修正或触发器也未核对。
- [单元测试日志](D:/program/develop/redis_project/campus_deal/target/codex-review-mvn-test.log)、[7 项核心检查](D:/program/develop/redis_project/campus_deal/target/review/probe-results.log)、[2 项 MVC 检查](D:/program/develop/redis_project/campus_deal/target/review/mvc-probe-results.log)、[Outbox 时间检查](D:/program/develop/redis_project/campus_deal/target/review/outbox-probe-results.log)。复现源码位于同目录，属于被 Git 忽略的 `target` 构建产物，清理构建时会移除。

**优先修复的问题**

1. **[P0] 匿名文件删除接口存在目录穿越。已验证路径越界。**

   [UploadController.java:55](D:/program/develop/redis_project/campus_deal/src/main/java/com/campusdeal/controller/UploadController.java:55) 直接将用户提交的 filename 与上传目录拼接后传给 `FileUtil.del`，没有规范化路径及目录边界校验。[MvcConfig.java:32](D:/program/develop/redis_project/campus_deal/src/main/java/com/campusdeal/config/MvcConfig.java:32) 又放行整个 `/upload/**`；旧的 GET 删除接口仍保留。父目录路径可以指向上传目录之外的文件，实际可删除范围取决于应用进程权限。

   修复：删除接口必须鉴权并验证文件归属，优先接收服务端生成的文件 ID；规范化路径并验证属于指定上传目录，拒绝父目录穿越及符号链接逃逸，移除 GET 删除入口。上传本身也应增加文件类型白名单和内容检查，目前直接保留任意扩展名并写入 nginx 静态目录。

2. **[P1] 优惠券写接口匿名开放，商户修改只有登录检查。已通过 MVC 复现。**

   [CouponController.java:32](D:/program/develop/redis_project/campus_deal/src/main/java/com/campusdeal/controller/CouponController.java:32) 的普通券和秒杀券新增接口都会被 `/coupon/**` 白名单放行，控制器、服务层没有权限判断。[MerchantController.java:73](D:/program/develop/redis_project/campus_deal/src/main/java/com/campusdeal/controller/MerchantController.java:73) 只判断是否登录，任何有效用户都可以提交任意商户 ID 的更新。

   修复：按 HTTP 方法区分公开查询与受保护写入，集中实现学生、商家、管理员权限；服务层验证商户归属。请求使用专门 DTO 和字段白名单，并校验价格、库存、时间窗及必填字段，避免直接绑定可持久化实体。

3. **[P1] Agent 会话缺少归属校验，可跨用户读取和覆盖。已隔离复现。**

   [SessionManager.java:41](D:/program/develop/redis_project/campus_deal/src/main/java/com/campusdeal/agent/SessionManager.java:41) 读取 Redis 后没有比较已保存的 `userId`，而是把调用者 userId 填入返回对象。已知另一个用户的 sessionId，就能通过历史接口读取其内容；继续聊天还会在保存时覆盖会话所有者。随机 sessionId 不能代替权限校验。

   修复：读取、写入、删除都验证 owner，未知 ID 的创建由服务端控制；可用 `agent:session:{userId}:{sessionId}` 加强隔离。同一会话的并发更新还需要串行化或版本校验，避免整段历史相互覆盖。

4. **[P1] 按仓库 DDL 初始化时，新 Outbox 消息不会被补偿扫描。已验证写入对象时间为空。**

   [OutboxServiceImpl.java:26](D:/program/develop/redis_project/campus_deal/src/main/java/com/campusdeal/service/impl/OutboxServiceImpl.java:26) 创建消息时不设置 createTime；[outbox.sql:13](D:/program/develop/redis_project/campus_deal/src/main/resources/db/outbox.sql:13) 的 create_time 默认也是 NULL，实体没有自动填充配置。调度器只查询 `create_time < 当前时间减一分钟`，NULL 不满足这个条件。Kafka 不可用时即使成功写入 PENDING，也可能一直不被处理。

   修复：通过迁移将时间字段设为非空并提供默认值，或者统一实现插入/更新时间填充；修复已有 NULL 记录。增加从真实建表脚本开始的“写入 PENDING → 到期扫描 → 订单落库”集成测试。

5. **[P1] 先写去重标记、后写订单，会把失败订单误判为已完成。已隔离复现。**

   [FlashDealConsumer.java:132](D:/program/develop/redis_project/campus_deal/src/main/java/com/campusdeal/mq/FlashDealConsumer.java:132) 的补偿入口先 SETNX，插入失败时没有清理标记。下一次补偿命中标记直接返回，[OutboxScheduler.java:60](D:/program/develop/redis_project/campus_deal/src/main/java/com/campusdeal/mq/OutboxScheduler.java:60) 随即把消息设为 PROCESSED。复现中数据库插入仅尝试一次且失败，第二次补偿却成功结束。

   批量消费也存在“写标记后、提交数据库前进程退出”的同类窗口，重投可能直接被跳过并 ack。修复：以已提交的数据库订单或事务内消费记录作为完成依据，Redis 标记只能用于加速；补偿需明确返回“已持久化、已存在、仍待处理”，不能把抢占失败等同于处理成功。单纯在 catch 里清标记无法覆盖进程崩溃。

6. **[P1] Kafka 的“不 ack 就自动重投”假设不成立。代码与框架语义核对。**

   [FlashDealConsumer.java:103](D:/program/develop/redis_project/campus_deal/src/main/java/com/campusdeal/mq/FlashDealConsumer.java:103) 吞掉批量落库异常，补偿写入失败也只记日志，随后正常返回，没有 nack、seek 或异常交给容器。MANUAL ack 控制提交时机，本身不会把消费位置退回；后续同分区更高 offset 的确认可能越过这一失败批次。

   修复：配置有界重试与明确恢复策略的错误处理器，失败时抛出相应异常或调用批量 nack；不可恢复消息持久化到死信或人工处理队列后再推进 offset，并对补偿失败告警。框架依据：[Spring Kafka 3.0.15 的 offset 提交与 nack 说明](https://docs.spring.io/spring-kafka/docs/3.0.15/reference/html/#committing-offsets)。此处未启动真实 broker 验证重投。

7. **[P1] 秒杀库存扣减与可靠订单记录之间有断点，恢复逻辑没有计入在途订单。代码推演。**

   [FlashDealServiceImpl.java:129](D:/program/develop/redis_project/campus_deal/src/main/java/com/campusdeal/service/impl/FlashDealServiceImpl.java:129) 先执行 Lua 扣库存、登记购买用户，之后才生成订单号并发送 Kafka。进程在这期间退出，或 Kafka 与 Outbox 同时不可写，会留下“库存已扣、用户不能重试、没有可靠订单事件”的状态。

   [FlashDealServiceImpl.java:210](D:/program/develop/redis_project/campus_deal/src/main/java/com/campusdeal/service/impl/FlashDealServiceImpl.java:210) 的恢复只用“活动库存 - 已落库订单数”，也没有恢复已购用户集合。例如 10 件库存已有 8 单进入 Kafka 但还没落库，Redis 状态丢失后预热为 10，再受理 10 个不同用户，消费者最终可插入 18 单，数据库没有库存条件更新来兜底。

   修复：明确库存的权威来源和订单预占状态，使扣减与待处理事件具有可靠的原子记录，再异步转发；补上失败释放、重复请求返回原订单、恢复期间暂停受理和消费进度对账。若采用 Redis 原子事件方案，还必须明确 Redis 持久化及灾难恢复边界。对外区分“已受理”和“已创建”，提供结果查询。

8. **[P1] 逻辑过期缓存无法重建，且实际详情路径缺少空值缓存。锁冲突已隔离复现。**

   [CacheClient.java:124](D:/program/develop/redis_project/campus_deal/src/main/java/com/campusdeal/utils/CacheClient.java:124) 将 `keyPrefix + id` 同时用作数据 key 和锁 key。过期只是 JSON 中的时间字段，Redis key 仍存在，因此 SETNX 一直失败，旧缓存不会后台刷新；若并发删除让抢锁偶然成功，finally 又会删除同名的新缓存。

   修复：使用独立锁命名空间、唯一持有者值与校验后释放；冷缓存重建也加互斥和二次检查。当前缓存 miss 查不到数据库记录时直接返回，并未写空值，需在实际使用的逻辑过期路径补齐防穿透处理。商户更新还应在事务提交后失效缓存，避免提交前删缓存后被读请求回填旧值。

9. **[P1] 输出安全校验发生在 SSE 原文发送之后。已隔离复现。**

   [AgentOrchestratorImpl.java:340](D:/program/develop/redis_project/campus_deal/src/main/java/com/campusdeal/agent/AgentOrchestratorImpl.java:340) 收到 token 就发送 chunk，完整回答结束后才运行 verifyNode。脱敏或不可靠内容拦截无法撤回已经发送的文本；[chat.js:376](D:/program/develop/redis_project/campus_deal/src/main/resources/static/js/chat.js:376) 的 done 处理还只更新 sessionId，不使用最终校正答案。

   修复：根据风险等级选择校验后发送，或带跨 chunk 边界缓冲的增量检查；前端也应正确处理最终校正/拒绝事件。仅在 done 时重绘不能防止已发送内容泄漏。此外，无工具回答会先同步生成一次、再流式生成一次，可合并以减少延迟和模型调用成本；给异步执行配置专用有界线程池、超时和客户端断开取消。

10. **[P1] Canal 没有接入应用生命周期，失效规则也与数据模型不一致。代码核查。**

    [CanalClientImpl.java:52](D:/program/develop/redis_project/campus_deal/src/main/java/com/campusdeal/canal/CanalClientImpl.java:52) 只有公开 start 方法，当前生产源码没有调用它，也没有启动生命周期钩子。即使接通，[CanalClientImpl.java:153](D:/program/develop/redis_project/campus_deal/src/main/java/com/campusdeal/canal/CanalClientImpl.java:153) 统一读取 afterColumns 的 `id`，而秒杀表主键是 voucher_id，DELETE 又应读取 beforeColumns；优惠券事件还会直接删除 Redis 秒杀库存，正常请求缺 key 时又将其设为 0，可能把可售活动变成售罄。

    修复：通过可配置生命周期启动/停止，按表和事件类型解析主键；把普通缓存失效与秒杀权威库存更新分开。部署前验证 INSERT、UPDATE、DELETE、断线重连及回放。

**随后处理的功能与质量问题**

11. **[P2] 新建秒杀活动最长约五分钟不能购买。代码核查。**

    [CouponServiceImpl.java:88](D:/program/develop/redis_project/campus_deal/src/main/java/com/campusdeal/service/impl/CouponServiceImpl.java:88) 写数据库、Redis，但没有更新布隆过滤器；[BloomFilterServiceImpl.java:79](D:/program/develop/redis_project/campus_deal/src/main/java/com/campusdeal/cache/BloomFilterServiceImpl.java:79) 默认五分钟重建。新活动在进入本地 Bloom 前通常被直接拒绝为不存在。应在事务提交后增量加入，并考虑多实例通知与重建竞态。活动时间元数据缺失/异常时目前放行，也应改为明确的恢复或拒绝策略。

12. **[P2] RAG 分块结束条件错误。已隔离复现。**

    [ClasspathDocumentLoader.java:102](D:/program/develop/redis_project/campus_deal/src/main/java/com/campusdeal/rag/ClasspathDocumentLoader.java:102) 到达文本末尾后仍减去 overlap，随后逐字推进并反复生成尾部块。100 字文档产生 51 块，增加 embedding 数据量、存储和重复召回。末块输出后应直接结束；测试应覆盖短文本、恰好块长和重叠边界，断言内容覆盖与重复比例。

13. **[P2] BM25 与向量索引使用不同粒度 ID，RRF 无法融合同一内容。代码核查。**

    [IndexBuilder.java:100](D:/program/develop/redis_project/campus_deal/src/main/java/com/campusdeal/rag/IndexBuilder.java:100) 写入向量时用 chunkId，BM25 却索引完整 documentId；[HybridRetrieverImpl.java:93](D:/program/develop/redis_project/campus_deal/src/main/java/com/campusdeal/rag/HybridRetrieverImpl.java:93) 只按 docId 累加分数，两个通道不能相互加分，还可能重复占据 topK。应统一索引粒度，或者在融合前显式把 chunk 映射到原文，同时保留 title/source 供引用。

14. **[P2] PGVector 缺少 PostgreSQL JDBC 驱动，配置 URL 也无法直接启用。已验证解析后的 classpath。**

    [PgVectorStoreImpl.java:146](D:/program/develop/redis_project/campus_deal/src/main/java/com/campusdeal/rag/PgVectorStoreImpl.java:146) 创建 PostgreSQL 数据源，但 [pom.xml:142](D:/program/develop/redis_project/campus_deal/pom.xml:142) 附近只有说明，没有驱动依赖；实际 classpath 无 `org.postgresql.Driver`。建议补齐驱动、扩展/表结构迁移和真实连接测试，并为向量存储增加明确启用开关。未启用向量存储时，应提前跳过 embedding，避免先支付外部调用成本再返回空检索。

15. **[P2] 登录验证码可重复使用，发送和校验缺少服务端限流。代码核查。**

    [UserServiceImpl.java:49](D:/program/develop/redis_project/campus_deal/src/main/java/com/campusdeal/service/impl/UserServiceImpl.java:49) 校验成功后未消费验证码，同一验证码在有效期内能重复签发 token。现有限流只在 Agent 聊天入口使用。建议对手机号、IP、校验失败次数限流，验证码比对与消费采用原子操作，处理并发首次注册；日志不要记录验证码、登录参数及完整 token。当前 sendCode 只写 Redis，真实短信投递仍需实现。

**其他有依据的改进点**

| 范围 | 当前问题与改进方向 |
| --- | --- |
| 点赞与关注 | [PostServiceImpl.java:168](D:/program/develop/redis_project/campus_deal/src/main/java/com/campusdeal/service/impl/PostServiceImpl.java:168) 先读 Redis、再改数据库、再写 Redis；同用户并发点赞可重复加计数。关注表没有用户对唯一约束。使用可幂等的点赞/取消操作、持久化唯一关系和计数对账。 |
| Feed 游标 | [PostServiceImpl.java:91](D:/program/develop/redis_project/campus_deal/src/main/java/com/campusdeal/service/impl/PostServiceImpl.java:91) 返回的 offset 只统计本页，没有在最小时间仍等于请求 max 时累加旧 offset。大量同毫秒帖子跨页时可能重复翻页。使用复合游标或正确累计同分值偏移。 |
| 查询性能 | [MerchantServiceImpl.java:250](D:/program/develop/redis_project/campus_deal/src/main/java/com/campusdeal/service/impl/MerchantServiceImpl.java:250) 每次附近查询全表加载并排序；帖子列表逐条查作者及点赞状态。改用空间查询/Redis GEO 分页和批量查询，按真实数据规模验证。 |
| 多实例确认 | [SensitiveGuardImpl.java:30](D:/program/develop/redis_project/campus_deal/src/main/java/com/campusdeal/security/SensitiveGuardImpl.java:30) 的待确认操作在单机 Map，负载均衡到另一实例会提示不存在；校验 owner 前 remove 也会让越权请求消耗他人的确认。可用 Redis 存储，原子完成归属校验与领取，绑定具体订单和操作参数。 |
| API 契约 | 参数校验不完整；分页、坐标、消息长度及订单金额缺边界。异常处理多返回 HTTP 200，使监控和客户端难区分失败。统一 DTO 校验、错误码和 HTTP 状态，订单 ID 在所有出口统一用字符串。 |
| 配置与交付 | 默认启用 local、硬编码数据库/Redis 口令和 Windows 上传路径；缺完整可复现的环境编排与版本化数据库迁移。拆分环境配置、外置参数和上传根目录，增加最小启动文档与迁移入口。 |
| 可观测性 | 当前主要靠日志，降级后容易被看作正常可用。监控 PENDING 最老年龄、FAILED 数、Kafka lag、已受理未落库订单、库存差值、缓存重建失败、RAG 通道状态和 LLM 超时。 |
| 代码维护 | 保留旧同步秒杀实现，与当前异步链路的库存语义不同；部分组件只定义未接入，例如 merchantCache。明确唯一业务入口，清理失效实现和大段注释，并同步 README 与实际行为。 |

**测试为什么没发现这些问题，以及建议的修复顺序**

[CacheClientTest.java:158](D:/program/develop/redis_project/campus_deal/src/test/java/com/campusdeal/utils/CacheClientTest.java:158) 把任意 SETNX 都 Mock 成成功，没有模拟 key 已存在的语义，后面甚至断言删除数据 key。[FlashDealConsumerTest.java:105](D:/program/develop/redis_project/campus_deal/src/test/java/com/campusdeal/mq/FlashDealConsumerTest.java:105) 只验证没有 ack，就注释认为会重投；OutboxScheduler 测试使用已经手动设置好 createTime 的对象，无法发现真实插入记录不可扫描的问题。

建议按以下顺序推进，每一步以新的行为断言作为完成条件：

1. 收紧文件、优惠券、商户、Agent 会话权限；加入匿名、普通用户、资源所有者、管理员四类权限测试。
2. 修复缓存锁和空值路径；使用独立 Redis 测试环境验证过期重建、并发回填及锁释放。
3. 修复 Outbox 时间字段、完成状态与消费者重试；用隔离 MySQL/Redis/Kafka 验证进程崩溃、数据库失败、重投、Redis 状态丢失及恢复对账。
4. 修复 SSE 检查顺序与 RAG 分块/融合/驱动，测试实际事件序列和真实检索结果，而不只测试 Mock 调用次数。
5. 补齐活动上线同步、分页和社交并发行为，再进行稳定负载下的吞吐与尾延迟测试。把上述回归纳入 CI，并把本地开发数据与测试数据隔离。

已有的唯一索引、Lua 原子扣减、参数化 SQL、订单工具归属校验和单元测试框架值得保留；需要重点修正的是各组件连接处的权限与失败语义。
