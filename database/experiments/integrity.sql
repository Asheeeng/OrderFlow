SELECT 'order_total_or_empty' AS check_name, COUNT(*) AS bad FROM orders o
  LEFT JOIN (SELECT order_id,SUM(unit_price*quantity) AS amount FROM order_items GROUP BY order_id) x ON x.order_id=o.id
  WHERE x.amount IS NULL OR x.amount<>o.total
UNION ALL SELECT 'cross_merchant',COUNT(*) FROM order_items oi
  JOIN products p ON p.id=oi.product_id JOIN orders o ON o.id=oi.order_id
  WHERE p.merchant_id<>o.merchant_id
UNION ALL SELECT 'product_without_inventory',COUNT(*) FROM products p
  LEFT JOIN inventory i ON i.product_id=p.id WHERE i.product_id IS NULL
UNION ALL SELECT 'invalid_owner_role',COUNT(*) FROM (
  SELECT m.id FROM merchants m JOIN users u ON m.user_id=u.id WHERE u.role<>'MERCHANT'
  UNION ALL SELECT o.id FROM orders o JOIN users u ON o.user_id=u.id WHERE u.role<>'CUSTOMER'
  UNION ALL SELECT a.id FROM addresses a JOIN users u ON a.user_id=u.id WHERE u.role<>'CUSTOMER'
  UNION ALL SELECT c.user_id FROM cart_items c JOIN users u ON c.user_id=u.id WHERE u.role<>'CUSTOMER'
) owners
UNION ALL SELECT 'payment_state_amount_time',COUNT(*) FROM orders o LEFT JOIN payments p ON p.order_id=o.id
  WHERE (o.status IN ('PAID','SHIPPED','COMPLETED'))<>(p.id IS NOT NULL)
    OR (p.id IS NOT NULL AND (p.amount<>o.total OR p.paid_at<o.created_at OR p.status<>'SUCCESS'))
UNION ALL SELECT 'shipment_state_time',COUNT(*) FROM orders o
  LEFT JOIN shipments s ON s.order_id=o.id LEFT JOIN payments p ON p.order_id=o.id
  WHERE (o.status IN ('SHIPPED','COMPLETED'))<>(s.id IS NOT NULL)
    OR (s.id IS NOT NULL AND (p.id IS NULL OR s.shipped_at<p.paid_at
       OR (o.status='COMPLETED' AND (s.status<>'DELIVERED' OR NOT (s.delivered_at <=> o.completed_at)))
       OR (o.status='SHIPPED' AND s.status<>'SHIPPED')))
UNION ALL SELECT 'inventory_balance',COUNT(*) FROM inventory i LEFT JOIN (
    SELECT product_id,SUM(delta) AS net FROM inventory_records GROUP BY product_id
  ) r USING (product_id)
  WHERE i.available<>i.opening_stock+COALESCE(r.net,0) OR i.available<0
UNION ALL SELECT 'missing_or_wrong_order_ledger',COUNT(*) FROM order_items oi JOIN orders o ON o.id=oi.order_id
  LEFT JOIN inventory_records r ON r.order_id=o.id AND r.product_id=oi.product_id AND r.reason='RESERVE'
  LEFT JOIN inventory_records x ON x.order_id=o.id AND x.product_id=oi.product_id AND x.reason='RELEASE'
  WHERE r.id IS NULL OR r.delta<>-oi.quantity OR r.created_at<o.created_at
    OR (o.status='CANCELLED')<>(x.id IS NOT NULL)
    OR (x.id IS NOT NULL AND (x.delta<>oi.quantity OR x.created_at<r.created_at))
UNION ALL SELECT 'unexpected_order_ledger',COUNT(*) FROM inventory_records r
  LEFT JOIN order_items oi ON oi.order_id=r.order_id AND oi.product_id=r.product_id
  WHERE r.order_id IS NOT NULL AND oi.id IS NULL;
