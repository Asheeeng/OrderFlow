USE orderflow;
CREATE INDEX idx_products_catalog ON products(category_id, active, id);
CREATE INDEX idx_products_name ON products(name);
CREATE INDEX idx_orders_user_status_date ON orders(user_id, status, created_at, id);
CREATE INDEX idx_orders_user_list ON orders(user_id, id);
CREATE INDEX idx_orders_merchant_list ON orders(merchant_id, id);
CREATE INDEX idx_orders_status_date ON orders(status, created_at, id);
CREATE INDEX idx_orders_date ON orders(created_at, id);
