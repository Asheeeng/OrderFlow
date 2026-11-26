SELECT VERSION() AS version,@@innodb_buffer_pool_size AS buffer_pool;
SHOW INDEX FROM orders;
SHOW INDEX FROM products;
SELECT '01-before: user/status/month, ignore relevant indexes for baseline' AS lab;
EXPLAIN SELECT id,user_id,status,created_at FROM orders
  IGNORE INDEX(idx_orders_user_status_date,idx_orders_user_list,idx_orders_status_date,idx_orders_date)
  WHERE user_id=1003 AND status='COMPLETED' AND created_at>='2026-01-01' AND created_at<'2026-12-01';
EXPLAIN ANALYZE SELECT id,user_id,status,created_at FROM orders
  IGNORE INDEX(idx_orders_user_status_date,idx_orders_user_list,idx_orders_status_date,idx_orders_date)
  WHERE user_id=1003 AND status='COMPLETED' AND created_at>='2026-01-01' AND created_at<'2026-12-01';

SELECT '02-after: covering composite index, same result' AS lab;
EXPLAIN SELECT id,user_id,status,created_at FROM orders
  WHERE user_id=1003 AND status='COMPLETED' AND created_at>='2026-01-01' AND created_at<'2026-12-01';
EXPLAIN ANALYZE SELECT id,user_id,status,created_at FROM orders
  WHERE user_id=1003 AND status='COMPLETED' AND created_at>='2026-01-01' AND created_at<'2026-12-01';

SELECT '03-secondary vs clustered: adding ship_to requires row lookup; PK directly finds row' AS lab;
EXPLAIN ANALYZE SELECT id,user_id,status,created_at,ship_to FROM orders
  WHERE user_id=1003 AND status='COMPLETED' AND created_at>='2026-01-01' AND created_at<'2026-12-01';
EXPLAIN ANALYZE SELECT ship_to FROM orders WHERE id=350000;

SELECT '04-leftmost prefix: sole user vs missing user under the target composite index' AS lab;
EXPLAIN FORMAT=JSON SELECT id,user_id,status,created_at FROM orders FORCE INDEX(idx_orders_user_status_date)
  WHERE user_id=1003;
EXPLAIN FORMAT=JSON SELECT /*+ NO_SKIP_SCAN(orders) */ id, user_id,status,created_at
  FROM orders FORCE INDEX(idx_orders_user_status_date) WHERE status='COMPLETED';
EXPLAIN FORMAT=JSON SELECT id,user_id,status,created_at FROM orders FORCE INDEX(idx_orders_user_status_date)
  WHERE user_id=1003 AND created_at>='2026-11-01';

SELECT '05-nonsargable: DATE(created_at) vs half-open time range' AS lab;
EXPLAIN ANALYZE SELECT COUNT(*) FROM orders WHERE DATE(created_at)='2026-11-11';
EXPLAIN ANALYZE SELECT COUNT(*) FROM orders WHERE created_at>='2026-11-11' AND created_at<'2026-11-12';

SELECT '06-leading wildcard vs prefix on the ordinary products name index' AS lab;
EXPLAIN ANALYZE SELECT COUNT(*) FROM products WHERE name LIKE '%收纳盒';
EXPLAIN ANALYZE SELECT COUNT(*) FROM products WHERE name LIKE '分隔收纳盒%';

SELECT '07-implicit conversion of the string SKU column vs a correctly typed equality' AS lab;
EXPLAIN ANALYZE SELECT id FROM products WHERE sku=123;
EXPLAIN ANALYZE SELECT id FROM products WHERE sku='OF-000123';

SELECT '08-deep pagination: identical result after finding cursor once' AS lab;
SET @anchor=(SELECT id FROM order_items ORDER BY id LIMIT 899999,1);
SET @anchor=COALESCE(@anchor,0);
EXPLAIN ANALYZE SELECT id,order_id,product_id FROM order_items ORDER BY id LIMIT 900000,10;
EXPLAIN ANALYZE SELECT id,order_id,product_id FROM order_items WHERE id>@anchor ORDER BY id LIMIT 10;
SELECT id,order_id,product_id FROM order_items ORDER BY id LIMIT 900000,10;
SELECT id,order_id,product_id FROM order_items WHERE id>@anchor ORDER BY id LIMIT 10;

SELECT '09-merchant sales stored procedure; price change trigger ledger' AS lab;
CALL merchant_daily_sales(1,'2026-11-10','2026-11-15');
SELECT product_id,old_price,new_price,changed_at FROM product_price_history ORDER BY id LIMIT 5;
