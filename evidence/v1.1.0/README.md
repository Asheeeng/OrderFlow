# OrderFlow V1.1.0 候选版验收

验收使用独立的 `orderflow-v110-verify` 项目、单独环境文件和卷。Apple Silicon macOS 上运行 Linux ARM64 容器。先用 `v1.0.1` 创建百万档基线，再在同一项目上迁移七张 AI 表。升级前后原 MySQL 主从和 Redis 容器 ID 一致，十三表逐表行数一致。原有演示实例继续使用各自的端口和数据。

受本机容器构建速度影响，升级实测的应用运行镜像使用同一候选源码在宿主工具链生成的 Java JAR 和 Vue 静态产物。初始化镜像及迁移脚本由项目 Dockerfile 构建，MySQL、Redis、Nginx、后端和 Mock 均实际在 Docker 容器中运行。原前后端多阶段 Dockerfile 保留，三个 Dockerfile 通过 AMD64 配置检查。候选版完整多阶段构建和 Windows 实机未完成本阶段验证。

## 三项课程标准

| 标准 | 已完成的实现与证据 | 验收状态 |
|---|---|---|
| RAG 智能问答 | 公共规则文档 4 篇、片段和向量各 10 条。顾客 Top-K 检索、来源引用、无关拒答，伪造引用拦截。管理员管理、增量更新和向量空间切换 | 23 项 Mock 通过，真实 Embedding 和生成待验 |
| Agent 工具调用 | 八工具、严格白名单、SQL 参数绑定、主从只读连接、多轮调用和数据图表。独立 SQL 核对、多工具、越权、注入、参数拦截、超时和 503 重试 | 19 项 Mock 通过，真实模型选择工具待验 |
| 数据与模型业务落地 | 商家库存、实付销量、趋势、可售天数、风险分类及模型有限行动建议。核对原始 SQL，阻止越权与无依据建议 | 10 项 Mock 通过，真实建议质量待验 |

Mock 模型标识为 `fixture-chat` 和 `fixture-embedding`，向量维度 11。模型响应由确定性本地 HTTP fixture 提供，业务数据库、Redis、Java 与网页均为实际运行。Token 是 fixture 返回的协议样本，实际费用无数据。Mock 耗时包含本机端到端请求，Agent 中位数 150 ms，客服 17 ms，库存 17.5 ms。Agent 专项超时样本为 8,051 ms。这些数值没有用于估计真实模型服务的性能。

真实模型凭据未提供，所以真实调用证据与正式 `v1.1.0` 标签均待完成。本地与两端仓库的已验证基线为 `v1.0.1`，未发现 `v1.0.2` 及 OrderFlow ACR 配置。国内镜像未发布。Windows/macOS 保留原部署入口，[升级步骤](../../docs/UPGRADE_V1.1.md) 与 [模型配置](../../docs/AI_CONFIG.md) 可分别执行。

## 评测后的数据库快照与资源

评测附加了合成业务样本。主从逐表一致，核心业务共 **3,213,056 行**，其中订单明细 **1,000,015 行**，九项完整性坏记录均为 0。取样时 GTID I/O 和 SQL 线程均为 Yes，延迟 0 秒。

| AI 表 | 主从各自行数 |
|---|---:|
| ai_knowledge_documents | 4 |
| ai_knowledge_chunks | 10 |
| ai_conversations | 87 |
| ai_messages | 163 |
| ai_tool_call_logs | 41 |
| ai_inventory_reports | 13 |
| ai_request_logs | 208 |

调用日志包括了第一次库存对照和完整重跑及浏览器用例。采样内存为后端 237.9 MiB、前端 6.027 MiB、Mock 13.71 MiB、主库 645.4 MiB、从库 700.7 MiB、Redis 10.87 MiB。卷的可见分配量分别是主库 962 MiB、从库 965 MiB、Redis 1 MiB。该数字来自一次实际采样、会随运行负载波动。

## 原系统回归与自动化记录

升级完成时核心数据共 **3,212,935 行**，订单明细 **1,000,000 行**。无密钥仍可登录、查询已正常运行的主从；原三角色、下单、模拟支付、发货、确认收货、取消、库存回补、超卖防护与 Redis 命中和失效均通过。测试项目重建容器后，数据库数据及 Redis AOF 持久化通过。Chrome 执行 6 项用例，其中 3 项原业务与 3 项新增页面全部通过。首次库存 SQL 对照测试使用了跨步骤的旧事务快照，改为逐条读取已提交数据后全套重跑通过。

- [升级后启动](compose-upgrade.txt) · [升级前后十三表](upgrade-core-counts.tsv.txt) · [无密钥验收](no-key.txt) · [回归与持久化](regression.txt)
- [52 项 Mock 逐例结果](mock-evaluation.jsonl.txt) · [向量空间切换](vector-space.txt) · [Chrome](chrome.txt)
- Mock 页面截图 [Agent](screenshots/mock-agent.png) · [客服](screenshots/mock-rag.png) · [库存](screenshots/mock-inventory.png)
- [主从精确行数、真实 SQL 检查和资源用量](db-and-resources.txt) · [AMD64 Dockerfile 配置检查](amd64-build-check.txt)

## 配置中心补充验收 2026-11-27

可视化配置中心已完成隔离 Mock 验收，新增密钥安全、四项连接测试、配置热切换和重建持久化。[配置中心报告及截图](config-center/README.md)
