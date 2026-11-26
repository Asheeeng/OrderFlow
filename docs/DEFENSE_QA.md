# OrderFlow 答辩题库

题目按教师评分标准第八节原顺序整理。口述答案需结合自己的复现过程与证据。

## 1. InnoDB 与 MyISAM 的区别？你的项目为什么选 InnoDB？

**口述答案（30–60 秒）**　InnoDB 支持事务、崩溃恢复、外键和行级加锁。MyISAM 以表锁为主，没有事务和外键。OrderFlow 的减库存、订单、明细和流水要原子提交，并且依赖外键约束关联，所以 13 张表都用 InnoDB。

**核心原理**　InnoDB 把整行放在主键聚簇树的叶子，维护 undo、redo 和 MVCC。MyISAM 的数据与索引分别存放，索引叶子指向数据文件位置。

**项目应用**　每个业务表都声明 ENGINE=InnoDB，订单服务可以回滚整次购物车结算。

**代码与 SQL**　`database/schema/001_tables.sql`，`backend/src/main/java/cn/orderflow/OrderService.java`

**可能追问**　哪种机制保证服务崩溃后已提交订单可重建？

**复现方法**　运行 `SHOW CREATE TABLE orders`，接着跑 `scripts/test-api.sh` 观察回滚用例。

## 2. 一条 SQL 在 MySQL 中大致经历哪些阶段？Buffer Pool 起什么作用？

**口述答案（30–60 秒）**　SQL 先经过连接、权限校验、解析与名称解析，然后优化器估算成本、选择计划，执行器通过存储引擎访问数据。Buffer Pool 缓存 InnoDB 的数据与索引页。重复读取可能命中内存，写入生成脏页并异步刷盘，事务持久性由日志与刷盘配置共同支撑。

**核心原理**　优化器使用统计信息估计行数，不一定选择理论上最窄的索引。Buffer Pool 页缓存与 SQL 结果没有一一对应关系。

**项目应用**　商品和订单页面数据来自数据库，索引优化实验用 EXPLAIN 展示估计，用 EXPLAIN ANALYZE 展示实跑。

**代码与 SQL**　`database/experiments/indexes.sql`，`scripts/index-lab.sh`

**可能追问**　热缓存和冷缓存的耗时为什么不同？

**复现方法**　执行 `SHOW GLOBAL STATUS LIKE 'Innodb_buffer_pool_read%'`，重复运行同一条聚簇查询并观察读计数。

## 3. 什么是 change buffer？它对写性能有什么影响？

**口述答案（30–60 秒）**　Change buffer 用于缓冲不在 Buffer Pool 里的部分非唯一二级索引页修改，以便以后读入页面或后台合并时应用。它可减少随机写入，也会引入合并和额外空间成本。OrderFlow 有多个订单二级索引，因此导入时需要关注索引写放大。

**核心原理**　唯一索引通常要及时访问目标页做唯一性检查。缓冲只针对受支持的操作和页，实际是否启用由服务器版本及配置决定。8.0 与 8.4 的默认配置有差异。

**项目应用**　查看运行实例的 innodb_change_buffering 与索引定义。项目未单独压测开启前后的变化。

**代码与 SQL**　`database/indexes/001_indexes.sql`，`docker-compose.yml`

**可能追问**　为什么唯一索引或包含降序列的索引不适用？

**复现方法**　执行 `SHOW VARIABLES LIKE 'innodb_change_buffering'` 与 `SHOW ENGINE INNODB STATUS\G`，观察 Change Buffer 段。

## 4. 为什么 MySQL 索引用 B+ 树而不是 B 树或哈希？

**口述答案（30–60 秒）**　InnoDB 常规索引采用 B+ 树。内部节点保存分隔键和页指针，扇出高，数据量增大时通常仍只访问少数层。叶子按键有序，可做范围扫描、排序和游标分页。哈希擅长等值查找，无法提供索引键的顺序。

**核心原理**　与内部节点存业务值的 B 树相比，B+ 树内部节点通常能容纳更多分隔键。相邻叶子链使范围扫描继续顺序访问。

**项目应用**　order_items 主键游标按 ID 读取连续叶子段，商品名称前缀也可使用有序的范围访问。

**代码与 SQL**　`database/experiments/indexes.sql`，`backend/src/main/java/cn/orderflow/OrderController.java`

**可能追问**　InnoDB 自适应哈希索引在什么场景可能有帮助？

**复现方法**　比较索引实验 06 和 08 中的 Index range scan 与无范围的扫描。

## 5. 聚簇索引与二级索引的区别？什么是回表？如何避免回表？

**口述答案（30–60 秒）**　聚簇索引的叶子页就是整行数据。二级索引叶子保存二级键及主键值。查询需要树上没有的列时，用主键再次查聚簇树，这一步称为回表。只读索引里已有的字段可形成覆盖查询，降低随机读。

**核心原理**　InnoDB 一张表只有一种物理聚簇顺序，二级索引可以有多个。是否需要回表取决于实际索引能否返回整份查询投影，不能只从 WHERE 判断。

**项目应用**　用户、状态、创建日期和 ID 由 idx_orders_user_status_date 覆盖，增加 ship_to 观察非覆盖访问。

**代码与 SQL**　`database/indexes/001_indexes.sql`，`database/experiments/indexes.sql`

**可能追问**　主键很宽为何会放大所有二级索引？

**复现方法**　运行实验 02 与 03，比对传统 EXPLAIN 的 Extra 及 ANALYZE 的迭代器。

## 6. 什么是最左前缀原则？举例说明你的索引设计。

**口述答案（30–60 秒）**　复合键按第一字段再按后续字段排序。订单索引从 user_id 开始，随后 status 与 created_at。给定用户或用户加状态能利用连续范围，跳过用户时，单靠后续状态一般不能按该复合键直接缩到窄范围。可为跨用户状态统计另建索引。

**核心原理**　前导等值后可用范围列，范围之后的列对索引定位能力要看具体查询，也可能用于覆盖或索引条件下推。优化器可能选择跳跃扫描或另一条索引。

**项目应用**　订单用户筛选和平台统计分别有配套索引。实验用 FORCE INDEX 和 NO_SKIP_SCAN 隔离观察。

**代码与 SQL**　`database/indexes/001_indexes.sql`，`database/experiments/indexes.sql`

**可能追问**　中间状态没有条件时，created_at 起什么作用？

**复现方法**　执行实验 04，观察 used_key_parts 与候选扫描行数。

## 7. 列举 3 种以上索引失效场景，并说明你项目中如何避免。

**口述答案（30–60 秒）**　项目演示了三个通常无法有效利用索引范围的写法。对日期列套 DATE()、在名称搜索开头加百分号、拿数字匹配字符串 SKU。可分别改成半开日期区间、名称前缀和类型正确的字符串等值。已有函数索引或不同优化器策略时要重新看计划。

**核心原理**　索引依赖原始键的顺序。表达式作用于列或将大量字符串先转换时，原索引上的匹配键可能不再是连续区间。全索引扫描也会显示使用某条索引。

**项目应用**　商品列表支持名称前缀，统计按 created_at 的半开区间查询，SKU 用字符串列及唯一约束。

**代码与 SQL**　`database/experiments/indexes.sql`，`backend/src/main/java/cn/orderflow/Catalog.java`

**可能追问**　后缀匹配一定只能全扫描吗？

**复现方法**　执行实验 05 至 07，解释每例访问方式与 examined rows。

## 8. 覆盖索引是什么？你项目中哪条查询用到了它？

**口述答案（30–60 秒）**　覆盖索引的含义是本次查询需要的字段全能从某个索引得到。项目按 user_id、status、created_at 检索并只展示这三列与 id 时，联合索引一次读取就能满足查询。多取收货地址之后需要读整行。

**核心原理**　Extra 中的 Using index 通常表示覆盖访问。Using index condition 是索引条件下推，可减少回表，但两者含义不同。索引宽度、查询频率和写成本需要一起取舍。

**项目应用**　idx_orders_user_status_date 对历史订单筛选投影形成覆盖。

**代码与 SQL**　`database/experiments/indexes.sql`，`database/indexes/001_indexes.sql`

**可能追问**　二级索引没显式写主键列时，主键能否被覆盖？

**复现方法**　运行实验 02、03，对比相同 WHERE 下添加 ship_to 前后的计划。

## 9. `EXPLAIN` 各字段含义？你如何判断一条 SQL 走没走索引？

**口述答案（30–60 秒）**　EXPLAIN 查看 type、possible_keys、key、key_len、rows、filtered 和 Extra。key 是实际选择，type 表示访问方式，rows 与 filtered 给出估计，Extra 可提示覆盖、排序及临时表。key 有值时仍可能是全索引扫描。实际耗时与行数要看 ANALYZE。

**核心原理**　id 和 select_type 描述查询块，table 指访问对象，ref 给出等值比较来源，key_len 协助判断用到了哪些前缀。估计存在统计误差。

**项目应用**　订单优化实验固定同一筛选，比较 IGNORE INDEX 基线与默认索引。

**代码与 SQL**　`database/experiments/indexes.sql`，`evidence/indexes.txt`

**可能追问**　type=index 与 type=range 有何区别？

**复现方法**　分别运行 EXPLAIN 与 EXPLAIN ANALYZE，指出 actual time、rows、loops 而非只看 key。

## 10. 深分页（`LIMIT 1000000, 10`）如何优化？

**口述答案（30–60 秒）**　深分页的 OFFSET 会跳过前面的很多记录，即使只返回十行也有读放大。稳定且可索引的顺序可改用游标，例如记住上一页末尾 id，下一页用 id>游标 ORDER BY id LIMIT 10。若按非唯一日期排序，要再用 id 作稳定并列排序。

**核心原理**　任意跳页与顺序翻页的访问需求有差别。若每次先用深 OFFSET 求游标，仍要付旧成本。新增或删除数据也会影响偏移分页的位置。

**项目应用**　订单和商品列表统一用服务端主键游标。实验在百万明细中比较同一批返回记录。

**代码与 SQL**　`backend/src/main/java/cn/orderflow/Db.java`，`database/experiments/indexes.sql`

**可能追问**　按 created_at DESC 查询如何处理同一秒的订单？

**复现方法**　运行实验 08，比较返回 ID 相同及两种计划访问行数。

## 11. 你优化过的最慢的一条 SQL 是什么？优化前后执行计划有何变化？

**口述答案（30–60 秒）**　本项目定位了订单日期筛选和深分页两类慢路径。慢日志对同一天的 DATE(created_at) 与半开区间做配对，两者结果相同，前者扫描宽范围，后者使用日期索引范围。深分页用上一页 ID，让扫描从游标位置开始。

**核心原理**　索引报告保留全表基线、覆盖区间和真实计时。慢日志使用本会话的零阈值，输出 query_time 和 rows_examined。单次计时受缓存和并行负载影响。

**项目应用**　运营日期查询用范围条件，核心列表采用游标分页。实测数字以索引日志为准。

**代码与 SQL**　`scripts/index-lab.sh`，`database/experiments/indexes.sql`，`evidence/indexes.txt`

**可能追问**　如何用更严格的实验区分缓存因素？

**复现方法**　运行 `scripts/index-lab.sh`，比较慢日志的 rows_examined 和同一查找返回量。

## 12. 事务的 ACID 如何实现？redo log 与 undo log 各起什么作用？

**口述答案（30–60 秒）**　原子性要求一起提交或一起撤销，undo 用于回滚并支持历史版本。redo 记录页修改并用于崩溃恢复。隔离性依靠锁和 MVCC。一致性需要约束和正确事务共同维持，例如库存开账余额加全部变动等于可用余额。

**核心原理**　WAL 要求相关日志先于脏页落盘，durability 还取决于 fsync 与同步设置。binlog 负责复制和时间点恢复，与 redo 的用途有区别。

**项目应用**　库存预占、订单、明细和流水在同一主库事务里提交或回滚。

**代码与 SQL**　`backend/src/main/java/cn/orderflow/OrderService.java`，`database/experiments/integrity.sql`

**可能追问**　提交后何种配置影响掉电时能否保住最后一笔事务？

**复现方法**　运行 API 回滚测试，随后运行完整性 SQL 对库存账。

## 13. 四种隔离级别分别解决什么问题？MySQL 默认是哪一级？

**口述答案（30–60 秒）**　READ UNCOMMITTED 可能读到未提交数据。READ COMMITTED 只能读已提交且每条普通读可以获得新快照。REPEATABLE READ 保持事务内的普通读快照，MySQL InnoDB 默认使用它。SERIALIZABLE 隔离最强，通常也增加锁等待。

**核心原理**　隔离等级的标准现象与 InnoDB 实现不能混为一谈。RR 普通一致性读用 MVCC，RR 当前读在合适索引范围用 next-key，RC 的间隙锁通常只保留在外键检查和查重等例外。

**项目应用**　应用写事务显式使用 RC，并对竞争行加锁。双会话实验比较 RR 与 RC。

**代码与 SQL**　`tests/lock_check.py`，`database/experiments/two-sessions.sql`

**可能追问**　SQL 标准里的 RR 与 MySQL InnoDB 的 RR 是否完全相同？

**复现方法**　查询 `SELECT @@transaction_isolation`，再运行 `scripts/lock-lab.sh`。

## 14. 什么是 MVCC？ReadView 如何判断可见性？

**口述答案（30–60 秒）**　MVCC 让读事务根据版本可见性读取行的历史值。ReadView 保存快照创建时活跃事务、事务 ID 边界和创建者，自己的写可见，明显早于活跃范围的已提交写可见，活跃事务或晚于快照的版本不可见。需要旧版本时沿 undo 版本链查。

**核心原理**　严格判断要用创建事务 ID、最小活跃 ID、快照时下一可分配事务 ID及活跃集。RC 每次普通读建立视图，RR 在事务内通常复用第一次一致性读的视图。

**项目应用**　同一商品名称在另一个会话提交后，RR 还看到原名，RC 看到新名。

**代码与 SQL**　`tests/lock_check.py`，`database/experiments/two-sessions.sql`

**可能追问**　长事务为何阻碍 purge 并增加 undo 膨胀？

**复现方法**　运行并发实验，然后分别解释 RR 和 RC 输出的两次读。

## 15. 幻读是如何产生的？InnoDB 如何解决？

**口述答案（30–60 秒）**　幻读指同一事务重复按范围读取时出现其他事务新增或移除的匹配行。InnoDB RR 的普通读取靠固定快照，锁定读则可通过记录及相邻间隙的 next-key lock 截住匹配范围的并发插入。主键单行的情况主要看记录锁。

**核心原理**　间隙锁保护的是有序索引上的间隙，包含末端间隙。是否存在索引及访问路径会影响锁的覆盖范围。

**项目应用**　购物车复合键按用户选择一个待插入的范围，另一会话插入时得到 1205。

**代码与 SQL**　`tests/lock_check.py`，`database/experiments/two-sessions.sql`

**可能追问**　为什么读不到任何行的索引范围也可能挡住插入？

**复现方法**　在两会话执行间隙锁片段，两个窗口最终都回滚。

## 16. 行锁、表锁、间隙锁、意向锁的区别？你的项目遇到过死锁吗，怎么处理？

**口述答案（30–60 秒）**　记录锁保护索引记录，表锁保护整表，间隙锁保护键之间的空位，意向锁标记事务计划持有表内的行锁，便于表锁快速判断冲突。项目用两个会话逆序锁两个库存 ID，MySQL 返回 1213。结算按主键排序抢锁，死锁时重新开始整个事务。

**核心原理**　死锁是等待图中的环。InnoDB 会选事务牺牲并回滚，通过 1213 报告。1205 表示等待超时，要观察具体业务是否重试。

**项目应用**　checkout 和 cancel 在 Service 层开启事务，控制器仅对 1213 限次退避。

**代码与 SQL**　`backend/src/main/java/cn/orderflow/OrderController.java`，`tests/lock_check.py`

**可能追问**　为什么在同一个已失败事务中重复 SQL 不是完整重试？

**复现方法**　运行 `scripts/lock-lab.sh` 复现 1205 和 1213，并检查用例的 finally 回滚。

## 17. 你的项目在哪些业务上用了事务？为什么选这个隔离级别？

**口述答案（30–60 秒）**　下单、取消、支付、发货、收货和库存调整都需要事务。关键订单写操作选 READ COMMITTED，配合针对用户、商品或订单的索引记录锁，以及带库存下限的条件 UPDATE。业务读取已提交结果，同时保持锁范围尽量小。

**核心原理**　RC 本身无法防超卖或重复支付。应用依靠受影响行数、订单状态机和唯一约束把并发条件写进数据库操作。库存调整事务使用项目主数据源的默认隔离级别。

**项目应用**　成功下单清空购物车，失败时库存余额、订单及流水全部回滚。

**代码与 SQL**　`backend/src/main/java/cn/orderflow/OrderService.java`，`backend/src/main/java/cn/orderflow/Catalog.java`

**可能追问**　同一订单支付与取消同时提交会怎样？

**复现方法**　运行 API 测试，展示冲突后余额和订单状态，以及 8 个并发请求的结果。

## 18. 自增主键与 UUID 主键各有什么优缺点？你如何选择？

**口述答案（30–60 秒）**　自增主键紧凑、按页尾顺序插入，并且适合局部数据库使用，能让二级索引里的主键后缀更短。UUID 便于独立节点生成，可跨服务合并，但随机 UUID 作为聚簇键会造成非顺序写入且占空间较大。项目采用 BIGINT 自增并接受间隙。

**核心原理**　自增序列不保证提交顺序、连续性或全局跨库唯一。UUID v7 的时序结构改善局部性，同时仍需考虑索引宽度。

**项目应用**　orders 与 order_items 用 BIGINT 主键，分页把最后一个实际键带到下一页。

**代码与 SQL**　`database/schema/001_tables.sql`，`database/experiments/indexes.sql`

**可能追问**　为什么回滚后 ID 可能出现空洞？

**复现方法**　查询 SHOW CREATE TABLE 与 AUTO_INCREMENT，再运行游标分页。

## 19. 为什么很多互联网公司不推荐使用外键？你怎么看？

**口述答案（30–60 秒）**　大规模拆库中，跨库外键难以维护，批量写和数据库变更也可能受联动约束。OrderFlow 使用单主数据库，数据强关联且规模可控，外键能在直接 SQL 误写时阻止孤儿数据。保留外键并采用限制删除，业务停用通过状态完成。

**核心原理**　外键需要查索引并可能锁父行，关联列必须具备适当索引。业务跨表一致性仍要靠事务和检查脚本。

**项目应用**　订单、支付、物流和库存变动都有明确的外键，数据库自动拒绝不存在的用户地址。

**代码与 SQL**　`database/schema/001_tables.sql`，`tests/http_check.py`

**可能追问**　外键默认 RESTRICT 对历史订单有什么好处？

**复现方法**　执行外键拒绝验证（错误 1216 或 1452）并查看 information_schema.KEY_COLUMN_USAGE。

## 20. 范式与反范式如何取舍？你的表设计中是否有反范式？

**口述答案（30–60 秒）**　范式把事实放在各自表，减少重复与更新异常。反范式适合有明确快照或读性能目标的字段。订单明细保存下单时商品名和成交价、订单保存收货地址及总额，历史信息随订单保存，读历史记录不用用当前商品价格重新推导。

**核心原理**　可以允许同一业务值存在多个表示，但要明确主写路径及一致性约束。订单总额用明细计算，保存后由完整性查询复核。

**项目应用**　用户和商家、产品和库存分开，历史快照和订单总额保存于订单域内。

**代码与 SQL**　`docs/DATABASE_DESIGN.md`，`database/experiments/integrity.sql`

**可能追问**　库存表同时存期初余额与当前余额有什么维护成本？

**复现方法**　在 API 里商品改价后读取旧订单，检查订单明细价格和支付额仍匹配。

## 21. binlog 有哪几种格式？分别适用什么场景？

**口述答案（30–60 秒）**　常见 binlog 格式有 STATEMENT、ROW 和 MIXED。STATEMENT 记录逻辑 SQL，体积可能较小但某些不确定语句难以重放。ROW 记录每行变化，复制更可预测。MIXED 根据语句安全性选方式。OrderFlow 显式使用 ROW 供复制和误删定位。

**核心原理**　ROW 中通常能找到 Table_map 与具体写、改、删行事件；事件内的数据可视性还受 binlog_row_image 等设置影响。

**项目应用**　恢复实验解码出删除 orders、order_items、inventory_records 的行事件。

**代码与 SQL**　`docker-compose.yml`，`scripts/binlog-lab.sh`，`evidence/binlog.txt`

**可能追问**　备份恢复为什么用完整事件输出，观察却用 DECODE-ROWS？

**复现方法**　执行 `SELECT @@binlog_format`，查看记录的 mysqlbinlog 行事件。

## 22. 描述你用 binlog 恢复数据的完整步骤。

**口述答案（30–60 秒）**　先在隔离源库做一致性备份并记下日志文件及偏移，写入正常订单，记录下一事务开始前的位置，再模拟误删。用 mysqlbinlog 解码确认误删事务。另一实例恢复备份，从快照偏移重放到误删事务开始前，检查原订单和新增订单都存在且库存正确。

**核心原理**　事务边界比只看 SQL 文本可靠。实验用同一 binlog 文件的起止偏移，回放排除误删事务。实际生产可能跨多个文件，还需保留备份、日志和操作审计。

**项目应用**　实验恢复订单 1、2，各自明细与库存余额都经 SQL 断言。

**代码与 SQL**　`scripts/binlog-lab.sh`，`database/backup`，`evidence/binlog.txt`

**可能追问**　如果误删之后还有合法写入，如何选恢复策略？

**复现方法**　运行 `scripts/binlog-lab.sh --isolated-run`，对照备份位点、删除 GTID 和恢复核验。

## 23. 主从复制的原理是什么？三个线程各自负责什么？

**口述答案（30–60 秒）**　主库提交后把事件写入 binlog。主库上的 Binlog Dump 线程向从库发事件，从库接收线程写入 relay log，从库 SQL 应用线程协调将 relay log 的事务应用。配置并行应用时还会有多个 worker。GTID 让从库按已执行事务集合请求缺少的事务。

**核心原理**　接收进度与执行进度可不同。重连时用 GTID auto position，已执行的事务不再重复应用。两个实例需要不同 server_id 和各自 server_uuid。

**项目应用**　OrderFlow 的 server_id 是 71 和 72，从库通过复制账号建立 GTID 通道。

**代码与 SQL**　`docker-compose.yml`，`database/replication/replica.sql`，`evidence/replication.txt`

**可能追问**　Retrieved_Gtid_Set 和 Executed_Gtid_Set 分别说明什么？

**复现方法**　执行 `SHOW REPLICA STATUS\G`，查看接收线程、执行线程及 Auto_Position。

## 24. 主从延迟如何产生、如何缓解？读写分离如何落地？

**口述答案（30–60 秒）**　主从延迟可能来自网络、长事务、从库负载或应用速度。页面的长期销售统计允许短暂延迟并显示复制状态，超过 60 秒或通道不健康时返回 503。下单后立即查订单等业务始终从主库读。验收用 GTID 等待特定事务实际抵达。

**核心原理**　Seconds_Behind_Source 是近似指标且可能为 NULL，应结合线程状态、GTID 集合和业务要求。容量优化或并行应用能减轻积压。

**项目应用**　管理统计显式注入 replicaJdbc，写事务和业务即时读取绑定主池。

**代码与 SQL**　`backend/src/main/java/cn/orderflow/AdminController.java`，`scripts/replication-lab.sh`

**可能追问**　若某一页面必须读到刚提交的数据，如何选择数据源？

**复现方法**　用实验脚本在主库写标记，等待从库 GTID，再用 Navicat 查标记。

## 25. 分库分表的时机和拆分策略？你的项目需要吗？

**口述答案（30–60 秒）**　当单机容量、热点写或维护窗口成为持续瓶颈，并且索引、分页、归档和读扩展都不能满足目标时，再考虑分片。拆分要选有明确路由的业务键，例如用户或商家，并评估跨商家订单和统计的代价。OrderFlow 当前先在两台实例上做索引与复制。

**核心原理**　水平分片需处理全局 ID、迁移、跨片事务和聚合。分区和分库分表解决的容量边界不同。

**项目应用**　用户侧订单局部性与商家侧履约都重要，贸然只按一个维度拆分会增加另一侧成本。

**代码与 SQL**　`docs/ARCHITECTURE.md`，`database/indexes/001_indexes.sql`

**可能追问**　按 merchant_id 拆分之后管理员的全平台计数如何做？

**复现方法**　统计订单与明细量，再比较用户和商家列表的现有执行计划。

## 26. Spring Boot 如何整合 MyBatis？`@Transactional` 失效的常见原因？

**口述答案（30–60 秒）**　Spring Boot 配置 DataSource 与事务管理器，MyBatis-Plus starter 扫描 Mapper 并把调用交给 SqlSessionTemplate。OrderFlow 的 UserMapper 使用 BaseMapper。事务通过 Spring 代理，常见失效点有自调用、对象手工 new、异常被吞或回滚规则不匹配，以及写到了其他数据源。

**核心原理**　事务管理器需与 Mapper 或 JdbcTemplate 使用同一个数据源。RuntimeException 默认回滚；若业务用受检异常要明确配置回滚规则。

**项目应用**　控制器调用容器代理的 OrderService，库存失败时抛运行时业务异常并回滚主库事务。

**代码与 SQL**　`backend/pom.xml`，`backend/src/main/java/cn/orderflow/UserMapper.java`，`backend/src/main/java/cn/orderflow/DatabaseConfig.java`

**可能追问**　在同一个事务里捕获异常而不向外传播会怎样？

**复现方法**　跑 API 回滚用例，然后核对订单、库存和流水。

## 27. 数据库连接池的作用？你用的哪种、如何配置？

**口述答案（30–60 秒）**　连接池保留可复用的数据库连接，避免每次 HTTP 请求都重新握手，并限制并发连接数。OrderFlow 使用 HikariCP，主池最多 16 个连接，从池最多 5 个连接，均设置 2.5 秒获取超时。从库连接设置只读并独立限流。

**核心原理**　池太小会排队，太大可能让 MySQL 整体竞争更重。SQL 长事务和并发量通常比机械加大连接数更值得先看。

**项目应用**　主库负责交易，统计池隔离来自从库的数据读取。

**代码与 SQL**　`backend/src/main/resources/application.yml`，`backend/src/main/java/cn/orderflow/DatabaseConfig.java`

**可能追问**　为什么 Hikari 的 readOnly 还要配合数据库账号和 super_read_only？

**复现方法**　启动应用后看两个 pool 名称，并用 SHOW PROCESSLIST 核对演示连接。

## 28. 你在项目中引入了哪个中间件？解决了什么问题？

**口述答案（30–60 秒）**　Redis 用来缓存商品详情中的名称、SKU、价格与分类等展示字段。详情先校验主库的上下架、版本和库存；缓存命中可省掉展示字段联查。商品更新提交后删除旧键，商品版本变化后自然切换到新键，旧键最多存 90 秒。Redis 断开时回读主库。

**核心原理**　版本键限缩并发回填污染新版本的风险。缓存不维护订单状态与可用余额，避免把 Redis 设成交易真值。

**项目应用**　管理员的运行状态页显示 Redis 连接、命中、未命中、降级和 TTL。

**代码与 SQL**　`backend/src/main/java/cn/orderflow/Catalog.java`，`scripts/runtime-check.sh`

**可能追问**　为何缓存未命中时还有数据库读取？

**复现方法**　读同一商品两次，再改价重读，查看命中、旧键和 TTL；运行降级脚本。

## 29. 画出你项目的核心表 ER 关系，说明主外键与级联策略。

**口述答案（30–60 秒）**　顾客关联地址、购物项和订单。商家与账户一对一，店铺有商品和订单。订单有多条明细，支付和发货通过唯一的 order_id 实现可选一对一。流水归属商品，预占与释放时还引用订单。项目使用限制删除来保留历史。

**核心原理**　inventory 的主键同时是商品外键，cart_items 用复合主键。跨商家归属和状态时间由事务及完整性查询补齐。应用用停用处理业务移除。

**项目应用**　ER 图含 13 张有效业务表，价格变动历史由触发器写入。

**代码与 SQL**　`docs/DATABASE_DESIGN.md`，`database/schema/001_tables.sql`

**可能追问**　为什么不对订单和支付使用级联删除？

**复现方法**　画顾客到订单、支付和商品的主链，再执行 SHOW CREATE TABLE order_items。

## 30. 如果数据量再涨 10 倍，你的系统会在哪里先出问题？怎么解决？

**口述答案（30–60 秒）**　十倍增长时先测订单聚合、全表精确 COUNT 和热点库存行，结合磁盘 I/O、慢查询和从库应用延迟确认瓶颈。读压力可用按日期增量统计和合理的数据归档缓解。用户侧列表保持游标分页，针对热门库存检查锁等待。

**核心原理**　把实时验收的精确 COUNT 与线上高频接口分离。对应成本包括扫描页面、二级索引空间和并发锁。容量方案先量化，确有必要时再评估拆分。

**项目应用**　系统把实时报表读到从库，并给出慢日志、计数脚本和库存并发实验。

**代码与 SQL**　`database/experiments/indexes.sql`，`backend/src/main/java/cn/orderflow/AdminController.java`

**可能追问**　如何决策何时从索引优化转向预聚合？

**复现方法**　运行执行计划和并发实验，记录返回行与扫描行的比例并讨论 10 倍变化。
