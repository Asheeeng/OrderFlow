# Navicat 主从连接与数据库检查

在 Navicat Premium 添加 MySQL 连接。主机填写 `127.0.0.1`，用户名为 `of_inspect`，密码取项目 `.env` 的 `DB_INSPECT_PASSWORD`。选中连接后的默认数据库为 `orderflow`。本机已验证两个连接可打开并列出该数据库。连接名称为 `OrderFlow 主库` 和 `OrderFlow 从库`，没有保存密码。

| 连接 | 端口 | 预期 server_id | read_only / super_read_only |
|---|---:|---:|---|
| 主库 | 13316 | 71 | 0 / 0 |
| 从库 | 13307 | 72 | 1 / 1 |

如需保留本机服务的 3306、3307 和已占用的 13306，这组端口可以共存。Compose 数据库只映射到回环地址。连接失败时依次在本机核对 `.env`、`docker compose ps` 以及实际连接的 `@@server_id`。

在两个连接中分别运行：

```sql
USE orderflow;
SELECT @@server_id,@@read_only,@@super_read_only,@@gtid_mode,VERSION();
SHOW TABLES;
SHOW CREATE TABLE order_items;
SHOW INDEX FROM orders;
SELECT COUNT(*) FROM order_items;
SELECT id,status,total FROM orders ORDER BY id DESC LIMIT 5;
SHOW CREATE PROCEDURE merchant_daily_sales;
CALL inspect_orderflow_triggers();
CALL merchant_daily_sales(1,'2026-11-10','2026-11-15');
```

从库再执行 `SHOW REPLICA STATUS;`，查阅 `Replica_IO_Running`、`Replica_SQL_Running`、`Seconds_Behind_Source` 和 `Auto_Position`。只读账号的触发器证据通过固定的检查过程查看，Navicat 原生触发器目录的可见性取决于 MySQL TRIGGER 权限。检查过程展示名称、事件、目标表、时机和语句，不开放 DDL 权限。可复制核心 [统计](../database/experiments/counts.sql)、[完整性](../database/experiments/integrity.sql) 和 [索引实验](../database/experiments/indexes.sql) 到 Navicat 查询窗口。并发实验需在演示主库用写账号打开两个独立连接。
