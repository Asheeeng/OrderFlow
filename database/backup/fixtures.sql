USE orderflow;
INSERT INTO users(id,username,password_hash,display_name,role) VALUES
 (1,'pitr_buyer','DISABLED','合成恢复顾客','CUSTOMER'),
 (2,'pitr_shop','DISABLED','合成恢复商家','MERCHANT');
INSERT INTO merchants(id,user_id,name) VALUES(1,2,'合成恢复商店');
INSERT INTO categories(id,name) VALUES(1,'合成恢复分类');
INSERT INTO products(id,merchant_id,category_id,sku,name,price) VALUES(1,1,1,'PITR-TEST','合成恢复商品',19.90);
INSERT INTO inventory VALUES(1,10,9);
INSERT INTO orders(id,user_id,merchant_id,status,total,ship_to) VALUES
 (1,1,1,'PENDING',19.90,'合成恢复地址');
INSERT INTO order_items(order_id,product_id,product_name,unit_price,quantity) VALUES(1,1,'合成恢复商品',19.90,1);
INSERT INTO inventory_records(product_id,order_id,reason,delta) VALUES(1,1,'RESERVE',-1);
