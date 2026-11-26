"""复现 MySQL 两个独立会话的 MVCC、锁等待和死锁。"""
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import json
from pathlib import Path
import threading

import pymysql

from http_check import connect, passed

port = "MYSQL_PRIMARY_PORT"


def error_code(cursor, sql, args, needed):
    try:
        cursor.execute(sql, args)
    except pymysql.MySQLError as error:
        assert error.args[0] == needed, error
        return
    raise AssertionError(f"应得到 MySQL {needed}")


def main():
    print(datetime.now(timezone.utc).isoformat(), "synthetic=true")
    product = json.loads(Path(".runtime/last-api-test.json").read_text())["product"]
    a, b = connect(port), connect(port)
    try:
        x, y = a.cursor(), b.cursor()
        y.execute("SELECT name FROM products WHERE id=%s", (product,))
        original, = y.fetchone()
        b.commit()
        try:
            for level in ("REPEATABLE READ", "READ COMMITTED"):
                x.execute(f"SET SESSION TRANSACTION ISOLATION LEVEL {level}")
                a.begin()
                x.execute("SELECT name FROM products WHERE id=%s", (product,))
                before, = x.fetchone()
                assert before == original
                y.execute("UPDATE products SET name='合成 MVCC 新版本' WHERE id=%s", (product,))
                b.commit()
                x.execute("SELECT name FROM products WHERE id=%s", (product,))
                after, = x.fetchone()
                assert (after == before) == (level == "REPEATABLE READ")
                a.commit()
                y.execute("UPDATE products SET name=%s WHERE id=%s", (original, product))
                b.commit()
                passed(f"{level} 两次普通读：{before!r} → {after!r}")
        finally:
            a.rollback()
            b.rollback()
            y.execute("UPDATE products SET name=%s WHERE id=%s", (original, product))
            b.commit()
        y.execute("SET SESSION innodb_lock_wait_timeout=1")
        a.begin()
        x.execute("SELECT product_id FROM inventory WHERE product_id=1 FOR UPDATE")
        error_code(y, "SELECT product_id FROM inventory WHERE product_id=1 FOR UPDATE", (), 1205)
        a.rollback()
        b.rollback()
        passed("主键等值行锁令同一行上的第二会话返回 1205")

        x.execute("SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ")
        a.begin()
        x.execute("SELECT user_id FROM cart_items WHERE user_id=1 AND product_id>=1 FOR UPDATE")
        error_code(y, "INSERT INTO cart_items(user_id,product_id,quantity) VALUES (1,%s,1)",
                   (product,), 1205)
        a.rollback()
        b.rollback()
        passed("REPEATABLE READ 范围锁住末端间隙，插入返回 1205")
    finally:
        a.close()
        b.close()

    lock = threading.Barrier(2, timeout=10)

    def opposite(first, second):
        db = connect(port)
        try:
            with db.cursor() as cursor:
                cursor.execute("SET SESSION innodb_lock_wait_timeout=5")
                db.begin()
                cursor.execute("SELECT product_id FROM inventory WHERE product_id=%s FOR UPDATE", (first,))
                lock.wait()
                cursor.execute("SELECT product_id FROM inventory WHERE product_id=%s FOR UPDATE", (second,))
                return 0
        except pymysql.MySQLError as error:
            return error.args[0]
        finally:
            db.rollback()
            db.close()

    with ThreadPoolExecutor(max_workers=2) as pool:
        left = pool.submit(opposite,1,2)
        right = pool.submit(opposite,2,1)
        result = sorted([left.result(),right.result()])
        assert result == [0,1213], result
    passed("逆序申请两个库存锁产生真实死锁，结果 0 / 1213，两会话均回滚")


if __name__ == "__main__":
    main()
