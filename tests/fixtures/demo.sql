INSERT OR IGNORE INTO groups(id,name,join_code) VALUES('demo-group','演示家庭','DEMO-LOCAL-ONLY');
INSERT OR IGNORE INTO users(id,username,group_id,is_elderly) VALUES
  ('demo-user','演示家人','demo-group',0),
  ('demo-elder','演示老人','demo-group',1);
INSERT OR IGNORE INTO warehouses(id,group_id,name) VALUES('demo-warehouse','demo-group','客厅药箱');
INSERT OR IGNORE INTO products(id,group_id,name,specification,manufacturer,base_unit,units_per_package,created_by)
  VALUES('demo-product','demo-group','测试药品（仅演示）','不含真实用药信息','演示资料','片',20,'demo-user');
INSERT OR IGNORE INTO stock_items(id,group_id,product_id,warehouse_id,expiry_date,units_per_package,units_remaining)
  VALUES('demo-box','demo-group','demo-product','demo-warehouse','2029-01-01',20,20);
INSERT OR IGNORE INTO medication_plans(id,group_id,user_id,product_id,medicine_name,amount,unit,times,frequency,start_date,created_by,updated_by)
  VALUES('demo-plan','demo-group','demo-user','demo-product','测试药品（仅演示）',1,'片','["08:00","20:00"]','daily','2020-01-01','demo-user','demo-user'),
  ('demo-plan-elder','demo-group','demo-elder','demo-product','测试药品（仅演示）',1,'片','["08:00","20:00"]','daily','2020-01-01','demo-user','demo-user');
