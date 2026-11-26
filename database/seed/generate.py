"""生成并导入 OrderFlow 合成数据。无真实个人信息。"""
import argparse
from collections import Counter, defaultdict
from datetime import datetime, timedelta, timezone
from decimal import Decimal
from itertools import islice
import os
from pathlib import Path
import random
from tempfile import TemporaryDirectory
import time

import bcrypt
from faker import Faker
import pymysql

SEED = 20261126
ORIGIN = datetime(2025, 1, 1)
DAYS = 687
FIXTURE_USERS = 3
MODES = {
    "small": (120, 8, 16, 160, 150, 100, 400),
    "million": (30000, 1000, 100, 20000, 40000, 30000, 400000),
}
GROUPS = ("家庭收纳", "文具办公", "轻型户外", "烹饪器具", "数码配件", "阅读用品", "家居布艺", "旅行用品")
ITEMS = ("分隔收纳盒", "便携笔记本", "折叠水壶", "量勺套装", "编织数据线", "护眼书灯", "棉麻靠垫", "轻量收纳袋")
DEMO_SKUS = ("DEMO-TRAIL", "DEMO-DESK", "DEMO-BAG")


def insert(cursor, table, columns, rows):
    fields = columns.split(",")
    statement = f"INSERT INTO {table} ({columns}) VALUES ({','.join(['%s'] * len(fields))})"
    rows = iter(rows)
    while batch := list(islice(rows, 2000)):
        cursor.executemany(statement, batch)


def catalog(spec, rng):
    _, merchants, categories, products, *_ = spec
    data = []
    by_shop = defaultdict(list)
    for product_id in range(1, products + 1):
        shop = (product_id - 1) % merchants + 1
        category = (product_id - 1) % categories + 1
        index = (category - 1) % len(GROUPS)
        sku = DEMO_SKUS[product_id - 1] if product_id <= len(DEMO_SKUS) else f"OF-{product_id:06d}"
        title = ITEMS[index] if product_id <= len(DEMO_SKUS) else f"{ITEMS[index]} {1 + (product_id - 1) // categories:02d}／{category:02d}"
        row = (product_id, shop, category, sku, title, Decimal(rng.randrange(1290, 79900, 10)) / 100)
        data.append(row)
        by_shop[shop].append(row)
    return data, by_shop


def reference_data(cursor, spec, products):
    users, merchants, categories, _, addresses, carts, _ = spec
    fake = Faker("zh_CN")
    fake.seed_instance(SEED)
    demo_hash = bcrypt.hashpw(os.environ["DEMO_PASSWORD"].encode(), bcrypt.gensalt(12)).decode()
    demo_users = [
        (1, "demo_customer", demo_hash, "演示顾客", "CUSTOMER"),
        (2, "demo_merchant", demo_hash, "演示商家", "MERCHANT"),
        (3, "demo_admin", demo_hash, "演示管理员", "ADMIN"),
    ]
    # 其他合成用户的密码列存不可验证标记。
    people = demo_users + [
        (i, f"synthetic_{i:06d}", "DISABLED", f"合成用户 {i:06d}",
         "MERCHANT" if i <= merchants + 2 else "CUSTOMER")
        for i in range(FIXTURE_USERS + 1, users + 1)
    ]
    insert(cursor, "users", "id,username,password_hash,display_name,role", people)
    insert(cursor, "merchants", "id,user_id,name", (
        (i, 2 if i == 1 else i + 2, "演示品牌店" if i == 1 else f"合成商店 {i:04d}")
        for i in range(1, merchants + 1)
    ))
    insert(cursor, "categories", "id,name", (
        (i, f"{GROUPS[(i - 1) % len(GROUPS)]} · {i:02d}") for i in range(1, categories + 1)
    ))
    insert(cursor, "products", "id,merchant_id,category_id,sku,name,price", products)
    insert(cursor, "inventory", "product_id,opening_stock,available", (
        (i, 10000, 10000) for i, *_ in products
    ))
    customer_ids = [1, *range(merchants + 3, users + 1)]
    insert(cursor, "addresses", "id,user_id,recipient,destination", (
        (i, customer_ids[(i - 1) % len(customer_ids)], "演示收件人" if i == 1 else f"样例收件人 {i:05d}",
         f"合成市 / {fake.color_name()}路 / 示例门牌 {i}") for i in range(1, addresses + 1)
    ))
    insert(cursor, "cart_items", "user_id,product_id,quantity", (
        (customer_ids[1 + i % (len(customer_ids) - 1)],
         1 + (i * 137 + i // (len(customer_ids) - 1)) % len(products), 1 + i % 3)
        for i in range(carts)
    ))
    cursor.execute("UPDATE products SET price=price+1 WHERE id=1")


def generate_orders(spec, by_shop, rng):
    users, merchants, _, _, _, _, order_limit = spec
    day_weights = [
        (3 if day.month in (6, 11, 12) else 1) * (1.5 if day.weekday() >= 5 else 1)
        for day in (ORIGIN + timedelta(days=i) for i in range(DAYS))
    ]
    days = rng.choices(range(DAYS), weights=day_weights, k=order_limit)
    statuses = ("PENDING", "CANCELLED", "PAID", "SHIPPED", "COMPLETED")
    for order_id, day in enumerate(days, 1):
        shop = rng.randrange(1, merchants + 1)
        # 将热门偏好限制在同店候选池内并保持同一订单商品不重复。
        pool = by_shop[shop]
        item_count = 2 + order_id % 2
        candidates = pool[:8] if rng.random() < .65 else pool
        chosen = rng.sample(candidates, item_count)
        buyer = merchants + 3 + int((rng.random() ** 2) * (users - merchants - 2))
        created = ORIGIN + timedelta(days=day, seconds=rng.randrange(86400))
        status = rng.choices(statuses, weights=(13, 12, 12, 13, 50))[0]
        paid = created + timedelta(minutes=rng.randrange(1, 360))
        shipped = paid + timedelta(hours=rng.randrange(2, 72))
        delivered = shipped + timedelta(days=rng.randrange(1, 5))
        cancelled = created + timedelta(minutes=40) if status == "CANCELLED" else None
        completed = delivered if status == "COMPLETED" else None
        entries = [(order_id, row[0], row[4], row[5], rng.randrange(1, 4)) for row in chosen]
        total = sum(entry[3] * entry[4] for entry in entries)
        order = (order_id, buyer, shop, status, total, f"合成市／历史收货快照 {buyer}", created, cancelled, completed)
        payment = (order_id, total, paid) if status in statuses[2:] else None
        shipment = ((order_id, f"OF-SYN-{order_id:09d}", "DELIVERED" if completed else "SHIPPED",
                     shipped, completed) if status in statuses[3:] else None)
        reserves = [(e[1], order_id, "RESERVE", -e[4], created) for e in entries]
        releases = [(e[1], order_id, "RELEASE", e[4], cancelled) for e in entries] if cancelled else []
        yield order, entries, payment, shipment, reserves + releases


def load_rows(cursor, folder, table, columns, rows):
    if not rows:
        return
    path = folder / (table + ".tsv")

    def field(value):
        if value is None:
            return r"\N"
        return str(value).replace("\\", "\\\\").replace("\t", r"\t").replace("\n", r"\n").replace("\r", r"\r")

    with path.open("w", encoding="utf-8", newline="") as data:
        for row in rows:
            data.write("\t".join(field(value) for value in row) + "\n")
    cursor.execute(f"""LOAD DATA LOCAL INFILE %s INTO TABLE {table} CHARACTER SET utf8mb4
        FIELDS TERMINATED BY '\\t' ESCAPED BY '\\\\' LINES TERMINATED BY '\\n' ({columns})""", (str(path),))
    if cursor.rowcount != len(rows) or cursor.warning_count:
        cursor.execute("SHOW WARNINGS LIMIT 5")
        raise RuntimeError(f"批量导入 {table} 出现警告或数量不符: {cursor.fetchall()}")


def write_orders(cursor, batch, folder):
    orders, items, payments, shipments, ledger = [], [], [], [], []
    deltas = Counter()
    for order, entries, payment, shipment, flows in batch:
        orders.append(order)
        items.extend(entries)
        if payment:
            payments.append(payment)
        if shipment:
            shipments.append(shipment)
        ledger.extend(flows)
        for product_id, _, _, delta, _ in flows:
            deltas[product_id] += delta
    load_rows(cursor, folder, "orders", "id,user_id,merchant_id,status,total,ship_to,created_at,cancelled_at,completed_at", orders)
    load_rows(cursor, folder, "order_items", "order_id,product_id,product_name,unit_price,quantity", items)
    load_rows(cursor, folder, "payments", "order_id,amount,paid_at", payments)
    load_rows(cursor, folder, "shipments", "order_id,tracking_no,status,shipped_at,delivered_at", shipments)
    load_rows(cursor, folder, "inventory_records", "product_id,order_id,reason,delta,created_at", ledger)
    # 每批对库存做一次集合更新，流水与库存仍处于同一事务。
    updated = [(product, delta) for product, delta in deltas.items() if delta]
    load_rows(cursor, folder, "seed_stock_delta", "product_id,delta", updated)
    cursor.execute("UPDATE inventory i JOIN seed_stock_delta d USING (product_id) SET i.available=i.available+d.delta")
    cursor.execute("DELETE FROM seed_stock_delta")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=MODES)
    args = parser.parse_args()
    spec = MODES[args.mode]
    rng = random.Random(SEED)
    products, by_shop = catalog(spec, rng)
    begin = time.monotonic()
    print(f"synthetic=true mode={args.mode} seed={SEED} start_utc={datetime.now(timezone.utc).isoformat()}", flush=True)
    conn = pymysql.connect(host=os.environ.get("MYSQL_PRIMARY_HOST", "127.0.0.1"),
                           port=int(os.environ["MYSQL_PRIMARY_PORT"]),
                           user="of_app", password=os.environ["DB_APP_PASSWORD"],
                           database="orderflow", charset="utf8mb4", autocommit=False, local_infile=True)
    try:
        with conn.cursor() as cursor, TemporaryDirectory(prefix="orderflow-seed-") as staging:
            folder = Path(staging)
            cursor.execute("SELECT GET_LOCK('orderflow_seed',0)")
            if cursor.fetchone()[0] != 1:
                raise RuntimeError("另一个种子导入正在执行")
            cursor.execute("SELECT COUNT(*) FROM users")
            if cursor.fetchone()[0] == 0:
                reference_data(cursor, spec, products)
                conn.commit()
                print(f"reference data committed users={spec[0]} products={spec[3]}", flush=True)
            cursor.execute("SELECT COUNT(*) FROM users WHERE id<=%s AND (username LIKE 'synthetic_%%' OR username IN ('demo_customer','demo_merchant','demo_admin'))", (spec[0],))
            actual_users = cursor.fetchone()[0]
            cursor.execute("SELECT COUNT(*) FROM users WHERE username LIKE 'synthetic_%%'")
            if actual_users != spec[0] or cursor.fetchone()[0] != spec[0] - FIXTURE_USERS:
                raise RuntimeError("数据库已有其他种子模式。请使用新的数据卷或新环境。")
            cursor.execute("SELECT COUNT(*),COALESCE(MAX(id),0) FROM orders WHERE id<=%s AND created_at<'2026-11-25'", (spec[-1],))
            count, completed = map(int, cursor.fetchone())
            if count != completed:
                raise RuntimeError("历史订单不连续，禁止自动续跑")
            if completed == spec[-1]:
                print(f"historical data already present orders={completed}", flush=True)
                return
            cursor.execute("SELECT COUNT(*) FROM orders WHERE id>%s", (completed,))
            if cursor.fetchone()[0]:
                raise RuntimeError("已产生实时订单，禁止继续在该库生成历史订单")
            cursor.execute("CREATE TEMPORARY TABLE seed_stock_delta (product_id BIGINT UNSIGNED PRIMARY KEY, delta INT NOT NULL) ENGINE=InnoDB")
            batches = iter(islice(generate_orders(spec, by_shop, rng), completed, None))
            print(f"generating and importing order batches from={completed+1} target={spec[-1]}", flush=True)
            while batch := list(islice(batches, 4000)):
                write_orders(cursor, batch, folder)
                conn.commit()
                completed = batch[-1][0][0]
                if completed % 20000 == 0 or completed == spec[-1] or args.mode == "small":
                    print(f"committed orders={completed} elapsed_s={time.monotonic()-begin:.2f}", flush=True)
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()


if __name__ == "__main__":
    main()
