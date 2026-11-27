"""Prepare a Compose database pair and run the existing OrderFlow seeder."""
from contextlib import closing
import os
from pathlib import Path
import re
import secrets as secure_random
import subprocess
import sys
import time

import bcrypt
import pymysql

DATABASE = Path(__file__).resolve().parent / "database"
PASSWORD_KEYS = ("DB_ROOT_PASSWORD", "DB_APP_PASSWORD", "DB_INSPECT_PASSWORD",
                 "DB_REPL_PASSWORD", "DEMO_PASSWORD")


def statements(path, replacements=None):
    """Read the repository's SQL scripts, including mysql-client DELIMITER directives."""
    sql = (DATABASE / path).read_text(encoding="utf-8")
    for token, value in (replacements or {}).items():
        sql = sql.replace(token, value)
    delimiter, lines = ";", []
    for line in sql.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("--"):
            continue
        if stripped.startswith("DELIMITER ") and not lines:
            delimiter = stripped.split(maxsplit=1)[1]
            continue
        if stripped.endswith(delimiter):
            lines.append(line.rstrip()[:-len(delimiter)])
            yield "\n".join(lines)
            lines.clear()
        else:
            lines.append(line)
    if lines:
        raise ValueError(f"Unterminated SQL statement: {path}")


def run_sql(cursor, path, replacements=None):
    for statement in statements(path, replacements):
        cursor.execute(statement)


def value(cursor, sql, parameters=()):
    cursor.execute(sql, parameters)
    return next(iter(cursor.fetchone().values()))


def socket_connection(service, password):
    return pymysql.connect(unix_socket=f"/run/orderflow/{service}/mysql.sock",
                           user="root", password=password, charset="utf8mb4",
                           autocommit=True, cursorclass=pymysql.cursors.DictCursor,
                           connect_timeout=10, read_timeout=330)


def configure_primary(cursor, secrets):
    run_sql(cursor, "schema/001_tables.sql")
    for statement in statements("indexes/001_indexes.sql"):
        index = re.match(r"CREATE INDEX (\w+) ON (\w+)", statement)
        if index and value(cursor, """
            SELECT COUNT(*) FROM information_schema.statistics
            WHERE table_schema='orderflow' AND table_name=%s AND index_name=%s
            """, (index[2], index[1])):
            continue
        cursor.execute(statement)

    for path in ("schema/002_routines.sql", "schema/003_inspection.sql"):
        for statement in statements(path):
            if statement.startswith("DROP "):
                continue
            object_name = re.match(r"CREATE (PROCEDURE|TRIGGER) (\w+)", statement)
            if object_name:
                metadata = ("routines", "routine") if object_name[1] == "PROCEDURE" else ("triggers", "trigger")
                if value(cursor, f"""
                    SELECT COUNT(*) FROM information_schema.{metadata[0]}
                    WHERE {metadata[1]}_schema='orderflow' AND {metadata[1]}_name=%s
                    """, (object_name[2],)):
                    continue
            cursor.execute(statement)

    run_sql(cursor, "replication/accounts.sql", {
        "@APP_PASSWORD@": secrets["DB_APP_PASSWORD"],
        "@INSPECT_PASSWORD@": secrets["DB_INSPECT_PASSWORD"],
        "@REPL_PASSWORD@": secrets["DB_REPL_PASSWORD"],
    })


def configure_replica(cursor, password):
    if not value(cursor, "SELECT @@GLOBAL.super_read_only"):
        cursor.execute("SET PERSIST super_read_only=ON")
    cursor.execute("SHOW REPLICA STATUS")
    status = cursor.fetchone()
    if not status:
        cursor.execute("""
            CHANGE REPLICATION SOURCE TO SOURCE_HOST='primary', SOURCE_PORT=3306,
            SOURCE_USER='of_replicator', SOURCE_PASSWORD=%s,
            SOURCE_AUTO_POSITION=1, SOURCE_SSL=1, GET_SOURCE_PUBLIC_KEY=1
            """, (password,))
    elif (status["Source_Host"], status["Source_Port"], status["Source_User"], status["Auto_Position"]) != (
            "primary", 3306, "of_replicator", 1):
        raise RuntimeError("Existing replica channel belongs to a different source or is not GTID-enabled")
    if not status or status["Replica_IO_Running"] == "No" or status["Replica_SQL_Running"] == "No":
        cursor.execute("START REPLICA")


def wait_for_replica(primary, replica):
    gtid = value(primary, "SELECT @@GLOBAL.gtid_executed")
    if value(replica, "SELECT WAIT_FOR_EXECUTED_GTID_SET(%s,300)", (gtid,)) != 0:
        raise RuntimeError("Replica did not apply the primary GTID set within 300 seconds")
    # WAIT can complete before the receiver has connected when the replica already has this set.
    for _ in range(30):
        replica.execute("SHOW REPLICA STATUS")
        status = replica.fetchone()
        if status["Replica_IO_Running"] == status["Replica_SQL_Running"] == "Yes":
            return
        time.sleep(1)
    raise RuntimeError("Replica receiver or applier is not running")


def verify_local_accounts(primary, secrets):
    with closing(pymysql.connect(host="replica", user="of_inspect",
                                  password=secrets["DB_INSPECT_PASSWORD"], database="orderflow",
                                  connect_timeout=10, read_timeout=10)) as client:
        with client.cursor() as cursor:
            cursor.execute("SELECT @@server_id, @@GLOBAL.super_read_only")
            if cursor.fetchone() != (72, 1):
                raise RuntimeError("The inspection account did not reach the read-only replica")
    primary.execute("""
        SELECT password_hash FROM orderflow.users
        WHERE username IN ('demo_customer','demo_merchant','demo_admin')
        """)
    accounts = primary.fetchall()
    if len(accounts) != 3 or not all(
        bcrypt.checkpw(secrets["DEMO_PASSWORD"].encode(), row["password_hash"].encode()) for row in accounts
    ):
        raise RuntimeError("Demo password does not match this volume; recover the original Docker environment file")


def main(secrets):
    mode = os.getenv("SEED_MODE", "million")
    if mode not in ("small", "million"):
        raise ValueError("SEED_MODE must be small or million")
    for key in PASSWORD_KEYS:
        limit = 32 if key == "DB_REPL_PASSWORD" else 72
        if not re.fullmatch(rf"[A-Za-z0-9]{{16,{limit}}}", secrets[key]):
            raise ValueError(f"{key} must be 16 to {limit} ASCII letters or digits")
    with closing(socket_connection("primary", secrets["DB_ROOT_PASSWORD"])) as source, \
            closing(socket_connection("replica", secrets["DB_ROOT_PASSWORD"])) as target:
        with source.cursor() as primary, target.cursor() as replica:
            if value(primary, "SELECT GET_LOCK('orderflow_compose_bootstrap',30)") != 1:
                raise RuntimeError("Another Compose initialization job holds the bootstrap lock")
            print("Preparing schema, accounts and GTID replica")
            configure_primary(primary, secrets)
            key_dir = Path("/run/orderflow-ai")
            key_dir.mkdir(parents=True, exist_ok=True)
            key_file = key_dir / "master.key"
            if not key_file.exists():
                if value(primary, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='orderflow' AND table_name='ai_runtime_config'"):
                    if value(primary, "SELECT COUNT(*) FROM orderflow.ai_runtime_config WHERE chat_secret IS NOT NULL OR embedding_secret IS NOT NULL"):
                        raise RuntimeError("Restore the AI master key volume before starting")
                temporary = key_dir / (".master." + secure_random.token_hex(8))
                fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                with os.fdopen(fd,"w") as output:
                    output.write(secure_random.token_hex(32))
                    output.flush()
                    os.fsync(output.fileno())
                os.chown(temporary,10001,10001)
                temporary.replace(key_file)
            for migration in sorted((DATABASE / "migrations").glob("V*__*.sql")):
                run_sql(primary, migration.relative_to(DATABASE))
                print(f"Schema ready: {migration.name}")
            configure_replica(replica, secrets["DB_REPL_PASSWORD"])
            wait_for_replica(primary, replica)
            subprocess.run([sys.executable, str(DATABASE / "seed/generate.py"), mode], check=True)
            # A long import can outlast idle sockets on a host with aggressive server settings.
            source.ping(reconnect=False)
            wait_for_replica(primary, replica)
            verify_local_accounts(primary, secrets)
            print(f"READY: mode={mode}; primary schema, seed, GTID replica and demo accounts verified")


if __name__ == "__main__":
    passwords = {key: os.getenv(key, "") for key in PASSWORD_KEYS}
    try:
        main(passwords)
    except Exception as error:
        message = str(error)
        for secret in passwords.values():
            if secret:
                message = message.replace(secret, "[redacted]")
        print(f"Initialization failed ({type(error).__name__}): {message}", file=sys.stderr)
        sys.exit(1)
