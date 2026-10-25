import sqlite3
import json
import os
import time
from typing import Dict, List, Optional

DB_PATH = os.path.join(os.path.dirname(os.path.dirname(__file__)), "classroom.db")

def get_connection():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn

def init_db():
    with get_connection() as conn:
        cursor = conn.cursor()
        
        # 1. 班级花名册成员表 (包含 score 积分字段)
        cursor.execute("""
        CREATE TABLE IF NOT EXISTS users (
            name TEXT PRIMARY KEY,
            role TEXT NOT NULL,
            avatar TEXT DEFAULT '',
            is_muted INTEGER DEFAULT 0,
            last_seen INTEGER NOT NULL,
            score INTEGER DEFAULT 0
        )
        """)
        
        cursor.execute("PRAGMA table_info(users)")
        user_cols = [row["name"] for row in cursor.fetchall()]
        if "score" not in user_cols:
            cursor.execute("ALTER TABLE users ADD COLUMN score INTEGER DEFAULT 0")

        # 2. 班级事务表（包含 deadline 截止时间）
        cursor.execute("""
        CREATE TABLE IF NOT EXISTS affairs (
            affair_id TEXT PRIMARY KEY,
            title TEXT NOT NULL,
            content TEXT NOT NULL,
            affair_type TEXT NOT NULL,
            publisher TEXT NOT NULL,
            deadline TEXT DEFAULT '',
            timestamp INTEGER NOT NULL,
            confirmed_students TEXT NOT NULL
        )
        """)
        
        cursor.execute("PRAGMA table_info(affairs)")
        columns = [row["name"] for row in cursor.fetchall()]
        if "deadline" not in columns:
            cursor.execute("ALTER TABLE affairs ADD COLUMN deadline TEXT DEFAULT ''")

        # 3. 随堂交流与系统消息历史表
        cursor.execute("""
        CREATE TABLE IF NOT EXISTS chat_history (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            room_id TEXT NOT NULL,
            msg_type TEXT NOT NULL,
            sender TEXT NOT NULL,
            role TEXT NOT NULL,
            avatar TEXT DEFAULT '',
            content TEXT NOT NULL,
            timestamp INTEGER NOT NULL
        )
        """)

        # 4. 系统全局配置表
        cursor.execute("""
        CREATE TABLE IF NOT EXISTS settings (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL
        )
        """)

        # 5. 班级图书角数据表
        cursor.execute("""
        CREATE TABLE IF NOT EXISTS books (
            book_id TEXT PRIMARY KEY,
            title TEXT NOT NULL,
            filename TEXT NOT NULL,
            file_size INTEGER NOT NULL,
            uploader TEXT NOT NULL,
            timestamp INTEGER NOT NULL
        )
        """)

        # 6. 新增：课堂考勤与积分明细流水表（支撑考勤日历功能）
        cursor.execute("""
        CREATE TABLE IF NOT EXISTS score_logs (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            student_name TEXT NOT NULL,
            delta INTEGER NOT NULL,
            reason TEXT NOT NULL,
            operator TEXT NOT NULL,
            date_str TEXT NOT NULL,
            timestamp INTEGER NOT NULL
        )
        """)

        conn.commit()

# ---------------- 考勤与积分日志持久化 ----------------

def add_user_score(name: str, delta: int = 1, reason: str = "课堂表现优异", operator: str = "教师") -> int:
    """增加或调整学生积分、写入考勤日历流水，并返回最新总分"""
    now_ts = int(time.time() * 1000)
    date_str = time.strftime("%Y-%m-%d", time.localtime(now_ts / 1000))
    with get_connection() as conn:
        cursor = conn.cursor()
        cursor.execute("UPDATE users SET score = coalesce(score, 0) + ? WHERE name = ?", (delta, name))
        cursor.execute("""
        INSERT INTO score_logs (student_name, delta, reason, operator, date_str, timestamp)
        VALUES (?, ?, ?, ?, ?, ?)
        """, (name, delta, reason, operator, date_str, now_ts))
        cursor.execute("SELECT score FROM users WHERE name = ?", (name,))
        row = cursor.fetchone()
        conn.commit()
        return row["score"] if row and row["score"] is not None else 0

def get_score_logs_by_date(date_str: str) -> List[dict]:
    """查询指定日期（YYYY-MM-DD）的所有加分/考勤明细"""
    logs = []
    with get_connection() as conn:
        cursor = conn.cursor()
        cursor.execute("""
        SELECT id, student_name, delta, reason, operator, date_str, timestamp
        FROM score_logs WHERE date_str = ? ORDER BY timestamp DESC
        """, (date_str,))
        for row in cursor.fetchall():
            logs.append({
                "id": row["id"],
                "student_name": row["student_name"],
                "delta": row["delta"],
                "reason": row["reason"],
                "operator": row["operator"],
                "date_str": row["date_str"],
                "timestamp": row["timestamp"]
            })
    return logs

def get_active_log_dates() -> List[str]:
    """获取所有存在加分记录的日期列表"""
    with get_connection() as conn:
        cursor = conn.cursor()
        cursor.execute("SELECT DISTINCT date_str FROM score_logs ORDER BY date_str DESC")
        return [row["date_str"] for row in cursor.fetchall()]

# ---------------- 图书角持久化 ----------------

def load_all_books() -> List[dict]:
    books = []
    with get_connection() as conn:
        cursor = conn.cursor()
        cursor.execute("""
        SELECT book_id, title, filename, file_size, uploader, timestamp
        FROM books ORDER BY timestamp DESC
        """)
        for row in cursor.fetchall():
            books.append({
                "book_id": row["book_id"],
                "title": row["title"],
                "filename": row["filename"],
                "file_size": row["file_size"],
                "uploader": row["uploader"],
                "timestamp": row["timestamp"]
            })
    return books

def insert_book(book_id: str, title: str, filename: str, file_size: int, uploader: str, timestamp: int):
    with get_connection() as conn:
        cursor = conn.cursor()
        cursor.execute("""
        INSERT OR REPLACE INTO books (book_id, title, filename, file_size, uploader, timestamp)
        VALUES (?, ?, ?, ?, ?, ?)
        """, (book_id, title, filename, file_size, uploader, timestamp))
        conn.commit()

def delete_book_db(book_id: str) -> Optional[str]:
    with get_connection() as conn:
        cursor = conn.cursor()
        cursor.execute("SELECT filename FROM books WHERE book_id = ?", (book_id,))
        row = cursor.fetchone()
        if row:
            filename = row["filename"]
            cursor.execute("DELETE FROM books WHERE book_id = ?", (book_id,))
            conn.commit()
            return filename
    return None

# ---------------- 用户数据持久化 ----------------

def load_all_users() -> Dict[str, dict]:
    users = {}
    with get_connection() as conn:
        cursor = conn.cursor()
        cursor.execute("SELECT name, role, avatar, is_muted, last_seen, score FROM users")
        for row in cursor.fetchall():
            users[row["name"]] = {
                "name": row["name"],
                "role": row["role"],
                "avatar": row["avatar"],
                "is_online": False,
                "is_muted": bool(row["is_muted"]),
                "last_seen": row["last_seen"],
                "score": row["score"] if row["score"] is not None else 0
            }
    return users

def upsert_user(name: str, role: str, avatar: str, is_muted: bool, last_seen: int):
    with get_connection() as conn:
        cursor = conn.cursor()
        cursor.execute("""
        INSERT INTO users (name, role, avatar, is_muted, last_seen)
        VALUES (?, ?, ?, ?, ?)
        ON CONFLICT(name) DO UPDATE SET
            role = excluded.role,
            avatar = CASE WHEN excluded.avatar != '' THEN excluded.avatar ELSE users.avatar END,
            is_muted = excluded.is_muted,
            last_seen = excluded.last_seen
        """, (name, role, avatar, 1 if is_muted else 0, last_seen))
        conn.commit()

def rename_user(old_name: str, new_name: str):
    with get_connection() as conn:
        cursor = conn.cursor()
        cursor.execute("UPDATE users SET name = ? WHERE name = ?", (new_name, old_name))
        cursor.execute("UPDATE score_logs SET student_name = ? WHERE student_name = ?", (new_name, old_name))
        conn.commit()

def delete_user_db(name: str):
    with get_connection() as conn:
        cursor = conn.cursor()
        cursor.execute("DELETE FROM users WHERE name = ?", (name,))
        cursor.execute("DELETE FROM score_logs WHERE student_name = ?", (name,))
        conn.commit()

# ---------------- 班级事务持久化 ----------------

def load_all_affairs() -> List[dict]:
    affairs = []
    with get_connection() as conn:
        cursor = conn.cursor()
        cursor.execute("""
        SELECT affair_id, title, content, affair_type, publisher, deadline, timestamp, confirmed_students
        FROM affairs ORDER BY timestamp DESC
        """)
        for row in cursor.fetchall():
            try:
                confirmed_list = json.loads(row["confirmed_students"])
            except Exception:
                confirmed_list = []
            affairs.append({
                "affair_id": row["affair_id"],
                "title": row["title"],
                "content": row["content"],
                "type": row["affair_type"],
                "publisher": row["publisher"],
                "deadline": row["deadline"] if row["deadline"] else "",
                "timestamp": row["timestamp"],
                "confirmed_count": len(confirmed_list),
                "confirmed_students": confirmed_list
            })
    return affairs

def insert_affair(affair_id: str, title: str, content: str, affair_type: str, publisher: str, deadline: str, timestamp: int):
    with get_connection() as conn:
        cursor = conn.cursor()
        cursor.execute("""
        INSERT OR REPLACE INTO affairs (affair_id, title, content, affair_type, publisher, deadline, timestamp, confirmed_students)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """, (affair_id, title, content, affair_type, publisher, deadline, timestamp, json.dumps([])))
        conn.commit()

def update_affair_db(affair_id: str, title: str, content: str, affair_type: str, deadline: str):
    with get_connection() as conn:
        cursor = conn.cursor()
        cursor.execute("""
        UPDATE affairs SET title = ?, content = ?, affair_type = ?, deadline = ?
        WHERE affair_id = ?
        """, (title, content, affair_type, deadline, affair_id))
        conn.commit()

def delete_affair_db(affair_id: str):
    with get_connection() as conn:
        cursor = conn.cursor()
        cursor.execute("DELETE FROM affairs WHERE affair_id = ?", (affair_id,))
        conn.commit()

def update_affair_confirmed(affair_id: str, confirmed_students: list):
    with get_connection() as conn:
        cursor = conn.cursor()
        cursor.execute("""
        UPDATE affairs SET confirmed_students = ? WHERE affair_id = ?
        """, (json.dumps(confirmed_students, ensure_ascii=False), affair_id))
        conn.commit()

# ---------------- 聊天记录持久化 ----------------

def load_recent_chat(room_id: str = "lobby", limit: int = 50) -> List[dict]:
    history = []
    with get_connection() as conn:
        cursor = conn.cursor()
        cursor.execute("""
        SELECT msg_type, sender, role, avatar, content, timestamp
        FROM chat_history WHERE room_id = ? ORDER BY id DESC LIMIT ?
        """, (room_id, limit))
        rows = cursor.fetchall()
        for row in reversed(rows):
            history.append({
                "type": row["msg_type"],
                "sender": row["sender"],
                "role": row["role"],
                "avatar": row["avatar"],
                "content": row["content"],
                "timestamp": row["timestamp"]
            })
    return history

def insert_chat_message(room_id: str, msg_type: str, sender: str, role: str, avatar: str, content: str, timestamp: int):
    with get_connection() as conn:
        cursor = conn.cursor()
        cursor.execute("""
        INSERT INTO chat_history (room_id, msg_type, sender, role, avatar, content, timestamp)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """, (room_id, msg_type, sender, role, avatar, content, timestamp))
        conn.commit()

def clear_chat_history_db(room_id: str = "lobby"):
    with get_connection() as conn:
        cursor = conn.cursor()
        cursor.execute("DELETE FROM chat_history WHERE room_id = ?", (room_id,))
        conn.commit()

# ---------------- 系统全局配置项 ----------------

def get_setting(key: str, default: str = "") -> str:
    with get_connection() as conn:
        cursor = conn.cursor()
        cursor.execute("CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        cursor.execute("SELECT value FROM settings WHERE key = ?", (key,))
        row = cursor.fetchone()
        return row["value"] if row else default

def set_setting(key: str, value: str):
    with get_connection() as conn:
        cursor = conn.cursor()
        cursor.execute("CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        cursor.execute("""
        INSERT INTO settings (key, value) VALUES (?, ?)
        ON CONFLICT(key) DO UPDATE SET value = excluded.value
        """, (key, value))
        conn.commit()