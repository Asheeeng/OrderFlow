SELECT o.id,o.status,o.total,i.product_name,i.quantity FROM orders o JOIN order_items i ON i.order_id=o.id ORDER BY o.id;
SELECT product_id,available FROM inventory ORDER BY product_id;
