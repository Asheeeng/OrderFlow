# OrderFlow 运行证据

这些文件来自本机真实操作及自动化测试，数据库样本为合成数据。截图由 Chrome 执行页面验收时生成。

| 文件 | 记录内容 |
|---|---|
| [seed-million.txt](seed-million.txt) | 百万档续跑进度、随机种子与 400,000 笔历史订单提交 |
| [seed-small.txt](seed-small.txt) | 独立空库的小规模写入及重复执行 |
| [data-check.txt](data-check.txt) | 主从精确行数、合计，以及 9 项完整性查询 |
| [indexes.txt](indexes.txt) | EXPLAIN、ANALYZE、慢日志、存储过程和价格触发器 |
| [api-acceptance.txt](api-acceptance.txt) | 角色、业务闭环、约束、并发及读库路由 |
| [concurrency.txt](concurrency.txt) | 两会话 MVCC、记录锁、间隙锁与死锁 |
| [binlog.txt](binlog.txt) | 隔离环境的备份、误删行事件、按位点重放与校验 |
| [replication.txt](replication.txt) | 主从参数、GTID、线程状态及新增同步 |
| [runtime-acceptance.txt](runtime-acceptance.txt) | Redis TTL、降级、AOF 与 Compose 停启持久化 |
| [navicat.txt](navicat.txt) | Navicat 实际连接结果和同连接参数的只读 SQL 核对 |
| [browser-acceptance.txt](browser-acceptance.txt) | Chrome 三角色端到端用例 |
| [screenshots](screenshots) | 实际平台统计、索引前后、恢复结果和复制状态画面 |

完整复现步骤及评分项证据见 [验收报告](../docs/ACCEPTANCE_REPORT.md) 和 [实验手册](../docs/MYSQL_EXPERIMENTS.md)。
