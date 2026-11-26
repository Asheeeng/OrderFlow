SELECT @@server_id AS server_id,@@read_only AS read_only,@@super_read_only AS super_read_only,VERSION() AS mysql;
SELECT COUNT(*) AS order_item_rows FROM order_items;
SELECT CONSTRAINT_NAME,REFERENCED_TABLE_NAME FROM information_schema.KEY_COLUMN_USAGE
WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='order_items' AND REFERENCED_TABLE_NAME IS NOT NULL;
SHOW INDEX FROM orders;
CALL inspect_orderflow_triggers();
CALL merchant_daily_sales(1,'2026-11-10','2026-11-15');
