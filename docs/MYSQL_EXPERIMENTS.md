# MySQL 技术实验与复现

所有证据来自本机 ARM64 Docker 的实际 MySQL 8.0.44。运行时长随缓存、主机负载和数据库规模变化。执行计划中 `actual time` 记录迭代器的首行及末行时间，单位为毫秒。本页的扫描行数可与证据文件逐项对照。

## 索引设计和执行计划

执行 `scripts/index-lab.sh`，或者在 Navicat 执行 [索引实验 SQL](../database/experiments/indexes.sql)。用 `SHOW INDEX` 对照索引列和顺序，读 `EXPLAIN` 的 key、type、rows、Extra 后，再用 `EXPLAIN ANALYZE` 对实际行数和时间做核查。项目基线用 IGNORE INDEX 暂时屏蔽适用索引，不删除生产表索引。实跑计划保存在 [索引证据](../evidence/indexes.txt)。

| SQL 场景 | 基线 | 改写及观察 |
|---|---|---|
| 用户、状态及日期查询 | 对订单扫描约 40 万行 | 联合覆盖索引范围扫描，同一条件返回 474 行。再取 ship_to 后需要访问整行 |
| 左前缀 | 省略第一列 user_id 时，实验限制目标索引并关闭跳跃扫描，无法将仅状态的条件直接用来定位窄范围 | 给出 user_id 可使用前缀。省略中间 status 时，日期可以过滤，使用的键前缀以 JSON 计划为准 |
| 日期表达式 | 对 created_at 套 DATE()，扫描 400,000 个键 | 半开区间检查 1,131 行，匹配行数一致 |
| 名称搜索 | 开头通配符使普通 B+ 树无法直接定位名称起始范围 | 页面提供名称前缀搜索。两个示例用来比较各自的访问方式 |
| SKU 类型 | 用数字与字符串列比较，触发列值转换并扫描 | 按实际字符串 SKU 等值检索，由唯一索引定位 |
| 明细深分页 | OFFSET 900,000 后返回 10 行，迭代器读 900,010 行 | 使用上一页末尾主键为游标，直接定位范围并读取 10 行，结果 ID 逐行一致 |

二级索引包括主键后缀。覆盖查询直接返回索引中的字段。只取某一订单的收货地址时，按主键直接到聚簇树叶子读取整行。添加二级索引会提高写入和磁盘成本，保留索引要有实际查询依据。

慢日志实验保留两条同结果语句的实测记录。第一条为 49.700 ms、检查 400,000 行，第二条为 0.477 ms、检查 1,131 行。脚本仅在一个测试会话临时使用零阈值，退出后恢复原主库日志开关及输出方式。示范时解释 query_time、lock_time 和 rows_examined 的差异。

## 事务和并发

运行 `scripts/test-api.sh` 验证同一结算不同商品的回滚范围、跨顾客对象隔离、重复支付和重复取消结果。验收启动 8 个隔离顾客同时购买 3 件库存，要求 3 次成交、5 个库存冲突，结余为 0。取消未支付订单时以对应流水加回库存。

接着运行 `scripts/lock-lab.sh`，或按 [双会话 SQL](../database/experiments/two-sessions.sql) 的 A、B 片段操作。读提交或可重复读下的普通读使用不同快照策略。主键等值锁定读等待超时为 1205。RR 范围锁实验观察另一会话插入受阻。两事务逆序申请两个库存行锁时会产生 MySQL 1213。验收脚本观察异常码、清理事务，并复原 MVCC 样本商品的名称。

业务在 READ COMMITTED 下使用按商品 ID 排序的条件更新和订单主键锁。SQL 返回的受影响行数验证扣减成功，订单状态与支付唯一键共同限制重复操作。死锁重试从一次全新的事务开始，最多追加两次，随机短暂退避。对照 [REST 验收](../evidence/api-acceptance.txt) 与 [锁实验](../evidence/concurrency.txt)。

## 日志备份和误删恢复

运行 `scripts/binlog-lab.sh --isolated-run`。它为源库和恢复目标创建两台无网络的临时 MySQL 容器，并使用项目业务表结构和两笔合成订单。同一 binlog 文件中的恢复过程如下。

1. 用 `mysqldump --single-transaction --source-data=2 --set-gtid-purged=OFF --no-tablespaces` 备份，快照位置为 `lab-bin.000003:10943`。
2. 写入正常订单 2。读取 `SHOW MASTER STATUS` 得到下一事务位置 11762。
3. 在隔离源库模拟误删订单 1、明细和库存流水，验证源库只剩订单 2。
4. 用 `docker cp` 复制该 binlog，由本机 `mysqlbinlog --base64-output=DECODE-ROWS -vv --start-position=11762` 定位订单删除和对应 GTID。
5. 把快照导入恢复目标。用 `mysqlbinlog --start-position=10943 --stop-position=11762 --skip-gtids` 输出可执行事件并重放，停止在误删事务的起点。
6. 恢复实例读取订单 1 和 2、成交额 19.90 和 39.80、库存余额 7、流水合计 −3。用 [恢复验证 SQL](../database/backup/verify.sql) 和 [本次恢复日志](../evidence/binlog.txt) 复查。

演练只使用本次创建的容器和匿名卷，由脚本清理。SQL 备份和复制出的二进制文件仅在本地保留。真实恢复流程还需要管理完整备份链、跨文件位置和恢复目标的读写切换。MySQL 的执行环境和日志格式选项可参看 [MySQL GTID 配置](https://dev.mysql.com/doc/refman/8.0/en/replication-gtids-howto.html)。

## GTID 主从与读写分离

运行 `scripts/replication-lab.sh`。主库写一条唯一分类，脚本读取主库执行 GTID，并通过 `WAIT_FOR_EXECUTED_GTID_SET` 在从库等待，再精确核对新增和清理。记录 `SHOW REPLICA STATUS`、I/O 与 SQL 线程、延迟、Auto_Position 和主从 ID。另运行 [数据检查脚本](../scripts/check-data.sh)，验证各业务表行数及跨表规则。主从配置与运行结果见 [Compose](../docker-compose.yml)、[复制 SQL](../database/replication/replica.sql)、[状态证据](../evidence/replication.txt)。

主库 Binlog Dump 向异步接收线程发送事件，接收线程落 relay log，从库协调器和应用线程执行事务。GTID 自动定位已经执行和尚缺失的事务。Spring Boot 显式注入两组连接池，读写路由以查询用途为准，从库异常时销售统计返回明确的 503。参看 [MySQL GTID 自动定位](https://dev.mysql.com/doc/refman/8.0/en/replication-gtids-auto-positioning.html)。

## Redis 和重启

调用商品详情两次可看见命中计数。商家改价提交后，旧键失效并由新版本键提供最新名称和价格。验收检查 Redis TTL，再停止 Redis 并调用同一详情。此时主库提供同样的业务字段。重启后读取 Redis AOF 持久化标记。随后停止并重新启动三项 Compose 服务，验证订单还在两边，GTID 可以继续追赶且从库仍为只读。结果见 [运行验收](../evidence/runtime-acceptance.txt)。

## 存储过程和触发器

执行 `CALL merchant_daily_sales(1,'2026-11-10','2026-11-15');`，同一个 SQL 按商家和日期返回订单和应计销售。它声明 SQL SECURITY INVOKER，并以已支付、发货、完成作为收入范围。触发器 `product_price_audit` 只在价格实际变化时写审计表。调价后在 Navicat 检查 product_price_history，旧订单仍保留其成交价。存储过程提供课堂独立实验入口，业务代码使用各自的数据源更新和统计。
