SELECT 'users' AS table_name,COUNT(*) AS row_count FROM users
UNION ALL SELECT 'merchants',COUNT(*) FROM merchants
UNION ALL SELECT 'categories',COUNT(*) FROM categories
UNION ALL SELECT 'products',COUNT(*) FROM products
UNION ALL SELECT 'inventory',COUNT(*) FROM inventory
UNION ALL SELECT 'addresses',COUNT(*) FROM addresses
UNION ALL SELECT 'cart_items',COUNT(*) FROM cart_items
UNION ALL SELECT 'orders',COUNT(*) FROM orders
UNION ALL SELECT 'order_items',COUNT(*) FROM order_items
UNION ALL SELECT 'payments',COUNT(*) FROM payments
UNION ALL SELECT 'shipments',COUNT(*) FROM shipments
UNION ALL SELECT 'inventory_records',COUNT(*) FROM inventory_records
UNION ALL SELECT 'product_price_history',COUNT(*) FROM product_price_history;
