USE orderflow;
DROP PROCEDURE IF EXISTS merchant_daily_sales;
DROP TRIGGER IF EXISTS product_price_audit;
DELIMITER //
CREATE PROCEDURE merchant_daily_sales(IN merchant BIGINT UNSIGNED, IN date_from DATE, IN date_to DATE)
SQL SECURITY INVOKER READS SQL DATA
BEGIN
  SELECT DATE(created_at) AS day, COUNT(*) AS orders, SUM(total) AS sales
  FROM orders
  WHERE merchant_id=merchant AND created_at>=date_from AND created_at<date_to
    AND status IN ('PAID','SHIPPED','COMPLETED')
  GROUP BY DATE(created_at) ORDER BY day;
END//
CREATE TRIGGER product_price_audit AFTER UPDATE ON products FOR EACH ROW
BEGIN
  IF OLD.price <> NEW.price THEN
    INSERT INTO product_price_history(product_id,old_price,new_price) VALUES(OLD.id,OLD.price,NEW.price);
  END IF;
END//
DELIMITER ;
