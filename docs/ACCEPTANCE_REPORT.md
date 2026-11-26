# OrderFlow V1.0 验收报告

本机验证日期为 2026-11-26（北京时间）。环境为 Apple Silicon、Docker Desktop、MySQL 8.0.44、Redis 7.4.2、Java 17。前端为 `http://127.0.0.1:5173`，API 为 `http://127.0.0.1:8080/api`。数据库记录来自固定种子的合成历史与本地验收操作。

## 教师评分项核验

| 评分项 | 验收 | 可检查证据 |
|---|---|---|
| 业务功能与工程完成度，8 分项 | 三类角色和角色权限通过。顾客注册、目录、购物车、下单、支付、发货、收货与取消经过实际接口。商家商品维护、库存和统计，管理员账号及商家启停、商品和订单管理通过 | [业务验收](../evidence/api-acceptance.txt)、[浏览器验收](../evidence/browser-acceptance.txt)、[启动脚本](../scripts/start.sh) |
| 索引与 SQL 优化，5 分项 | 订单基线扫描 400,000 行，覆盖范围扫描返回同样的 474 行。日期慢查询检查 400,000 → 1,131 行。深页检查 900,010 → 10 行 | [可执行 SQL](../database/experiments/indexes.sql)、[执行计划与慢日志](../evidence/indexes.txt)、[对比截图](../evidence/screenshots/index-comparison.png) |
| 事务与并发，3 分项 | 下单失败全量回滚。8 并发争抢 3 件，3 成功、5 个库存冲突。重复支付和取消返回 409。复现 RR、RC、1205 和死锁 1213 | [业务验收](../evidence/api-acceptance.txt)、[双会话 SQL](../database/experiments/two-sessions.sql)、[并发日志](../evidence/concurrency.txt) |
| 主外键与约束，2 分项 | 数据库拒绝非法外键、负库存、重复支付和 NULL 地址。9 项跨表检查均为 0 | [建表 SQL](../database/schema/001_tables.sql)、[完整性结果](../evidence/data-check.txt) |
| binlog 恢复，3 分项 | ROW 日志定位误删事务，恢复备份前已有的订单 1 和之后新增的订单 2，验证库存余额 7 | [恢复日志](../evidence/binlog.txt)、[恢复截图](../evidence/screenshots/binlog-recovery.png)、[恢复脚本](../scripts/binlog-lab.sh) |
| 主从复制及读写分离，2 分项 | I/O 与 SQL 均为 Yes，GTID 自动定位启用，采样延迟 0 秒。从库禁止更新。写业务库 71，统计读从库 72 | [复制配置](../docker-compose.yml)、[状态日志](../evidence/replication.txt)、[状态截图](../evidence/screenshots/replica-status.png) |
| 数据规模与规范，5 分项 | 13 张业务表，累计 3,213,008 行，明细 1,000,007 行。主从逐表精确行数一致 | [生成日志](../evidence/seed-million.txt)、[COUNT 与核验](../evidence/data-check.txt)、[核心统计 SQL](../database/experiments/counts.sql) |
| 文档与交付，2 分项 | 完整源码、依赖锁、配置、启动及验收命令、架构图、ER 图和数据字典均已归档 | [README](../README.md)、[架构](ARCHITECTURE.md)、[ER 图与表说明](DATABASE_DESIGN.md)、[技术实验](MYSQL_EXPERIMENTS.md) |
| 现场答辩材料，40 分项 | 已准备现场顺序和原版 30 道题，每题含口述、原理、应用、代码与 SQL、追问、复现 | [现场演示](DEMO_GUIDE.md)、[答辩题库](DEFENSE_QA.md)、[建议分工](TEAM_TASKS.md) |

本文件记录技术验收。现场演示、个人问答、平时实验报告和最终成绩由课程评定。AI、RAG 和 Agent 属于 V1.1 后续范围，建议见 [系统架构](ARCHITECTURE.md)。

## 补充验收

- Redis 实测 TTL 90 秒。关停 Redis 后，商品详情 HTTP 200 且业务字段相同。AOF 持久化标记在重启后可读。Compose 三服务停启后，订单 400002 在主从库一致，从库仍为只读。见 [运行验收](../evidence/runtime-acceptance.txt)。
- Navicat Premium 实际用只读账号打开主库和从库并看到了 `orderflow`。同参数的 TCP 交叉检查验证了索引、明细行数、外键、存储过程、触发器和拒绝写入。见 [检查记录](../evidence/navicat.txt)。
- 小规模模式在另建的隔离 MySQL 中通过两次执行，保留 400 笔历史订单及 1,000 行明细，9 项完整性核验为 0。见 [小规模生成结果](../evidence/seed-small.txt)。
- Million 导入最初因续跑数值类型出错，修正后保留已写入的基础表。随后从已提交的 52,000 笔历史订单断点切换到批量 LOAD DATA，并生成到 400,000 笔。公开证据含续跑时间与完成检查。
- 早期浏览器等待阈值与自动化断言在实机校准后，三项浏览器验收均通过。binlog 演练也针对实际备份注释格式及镜像自带工具作了兼容，最终结果见真实日志。
- 两个已有远程的初始提交都纳入本地历史，GitCode 初始 MIT 模板中的版权占位符用 GitHub 现有版权声明统一。同步脚本在独立 bare 仓库测试过快进、带注释标签和拒绝覆盖远程新提交。发布以两个远端实际查询到的 `main` 和 `v1.0.0` 为准。

## 精确数据快照

| 表名 | 记录数 | 表名 | 记录数 |
|---|---:|---|---:|
| users | 30,014 | merchants | 1,000 |
| categories | 100 | products | 20,005 |
| inventory | 20,005 | addresses | 40,014 |
| cart_items | 30,005 | orders | 400,007 |
| order_items | 1,000,007 | payments | 299,976 |
| shipments | 252,251 | inventory_records | 1,119,621 |
| product_price_history | 3 | **合计** | **3,213,008** |

截图和日志对应各自的记录时点。后续重新运行验收会添加新的合成业务记录，行数以当时的 COUNT(*) 为准。
