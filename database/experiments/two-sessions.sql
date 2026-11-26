-- 在 Navicat 两个独立连接窗口使用主库。以有写权限的 of_app 操作。
-- 以下每个片段按注释中的会话顺序逐段执行，不要执行整个文件。
USE orderflow;

-- 行锁 / 并发防超卖对应的同一条库存记录
-- A
START TRANSACTION;
SELECT product_id,available FROM inventory WHERE product_id=1 FOR UPDATE;
-- B（这条 SELECT 等待约 2 秒并返回 1205）
SET SESSION innodb_lock_wait_timeout=2;
START TRANSACTION;
SELECT product_id,available FROM inventory WHERE product_id=1 FOR UPDATE;
-- A 和 B 都执行
ROLLBACK;

-- 间隙锁（演示前检查顾客 1 没有购物车记录，商品 1 存在）
-- A
SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ;
START TRANSACTION;
SELECT product_id FROM cart_items WHERE user_id=1 AND product_id>=1 FOR UPDATE;
-- B（插入等待超时）
START TRANSACTION;
INSERT INTO cart_items(user_id,product_id,quantity) VALUES(1,1,1);
-- A 和 B
ROLLBACK;

-- 死锁（推荐用 tests/lock_check.py 的屏障自动复现）
-- A
START TRANSACTION;
SELECT product_id FROM inventory WHERE product_id=1 FOR UPDATE;
-- B
START TRANSACTION;
SELECT product_id FROM inventory WHERE product_id=2 FOR UPDATE;
-- A（令此查询保持等待）
SELECT product_id FROM inventory WHERE product_id=2 FOR UPDATE;
-- B（会话之一得到 1213）
SELECT product_id FROM inventory WHERE product_id=1 FOR UPDATE;
-- A 和 B
ROLLBACK;

-- MVCC（把 @p 替换为同一条验收商品 ID）
-- A,b 默认为不同物理连接。以下步骤用 tests/lock_check.py 会自动还原名称。
-- A
SET @p=1;
SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ;
START TRANSACTION;
SELECT name FROM products WHERE id=@p;
-- B（记录原名后提交名称新版本）
SET @p=1;
START TRANSACTION;
UPDATE products SET name='合成 MVCC 新版本' WHERE id=@p;
COMMIT;
-- A（普通读仍显示旧名称）
SELECT name FROM products WHERE id=@p;
ROLLBACK;
-- B 完成后用事先记录的原名还原；将 A 改为 READ COMMITTED 重做观察新版本。
