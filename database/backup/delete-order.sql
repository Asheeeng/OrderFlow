-- 仅用于 scripts/binlog-lab.sh 创建的无网络临时容器。
USE orderflow;
START TRANSACTION;
DELETE FROM inventory_records WHERE order_id=1;
DELETE FROM order_items WHERE order_id=1;
DELETE FROM orders WHERE id=1;
UPDATE inventory SET available=available+1 WHERE product_id=1;
COMMIT;
