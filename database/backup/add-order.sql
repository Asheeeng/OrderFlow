USE orderflow;
START TRANSACTION;
INSERT INTO orders(id,user_id,merchant_id,status,total,ship_to) VALUES(2,1,1,'PENDING',39.80,'合成新增地址');
INSERT INTO order_items(order_id,product_id,product_name,unit_price,quantity) VALUES(2,1,'合成恢复商品',19.90,2);
UPDATE inventory SET available=available-2 WHERE product_id=1;
INSERT INTO inventory_records(product_id,order_id,reason,delta) VALUES(1,2,'RESERVE',-2);
COMMIT;
