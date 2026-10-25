import time
from typing import Dict, List, Optional, Set
from fastapi import WebSocket
from games.registry import GameRegistry
from games.base import BaseGame
from core import db

class ClassAffair:
    """班级事务项：支持公告与作业两种形态"""
    def __init__(self, affair_id: str, title: str, content: str, affair_type: str, publisher: str, deadline: str = "", timestamp: int = None, confirmed_students: Set[str] = None):
        self.affair_id = affair_id
        self.title = title
        self.content = content
        self.affair_type = affair_type
        self.publisher = publisher
        self.deadline = deadline
        self.timestamp = timestamp if timestamp else int(time.time() * 1000)
        self.confirmed_students: Set[str] = confirmed_students if confirmed_students is not None else set()

    def to_dict(self) -> dict:
        return {
            "affair_id": self.affair_id,
            "title": self.title,
            "content": self.content,
            "type": self.affair_type,
            "publisher": self.publisher,
            "deadline": self.deadline,
            "timestamp": self.timestamp,
            "confirmed_count": len(self.confirmed_students),
            "confirmed_students": list(self.confirmed_students)
        }

class RoomInfo:
    def __init__(self, room_id: str, title: str, creator: str, max_players: int = 4, game_type: str = "rps"):
        self.room_id = room_id
        self.title = title
        self.creator = creator
        self.max_players = max_players
        self.game_type = game_type
        self.game: BaseGame = GameRegistry.create_game(game_type)
        self.members: Dict[WebSocket, dict] = {}
        self.created_at = int(time.time() * 1000)
        self.history: List[dict] = db.load_recent_chat(room_id, limit=50)
        self.canvas_strokes: List[dict] = []  # 小组协同白板笔迹缓存

    def get_student_count(self) -> int:
        return sum(1 for m in self.members.values() if m.get("role") != "teacher")

    def is_full(self, is_teacher: bool = False) -> bool:
        if is_teacher:
            return False
        return self.get_student_count() >= self.max_players

    def add_message(self, chat_item: dict):
        self.history.append(chat_item)
        if len(self.history) > 50:
            self.history.pop(0)
        db.insert_chat_message(
            room_id=self.room_id,
            msg_type=chat_item.get("type", "chat"),
            sender=chat_item.get("sender", "未知"),
            role=chat_item.get("role", "student"),
            avatar=chat_item.get("avatar", ""),
            content=chat_item.get("content", ""),
            timestamp=chat_item.get("timestamp", int(time.time() * 1000))
        )

class RoomManager:
    def __init__(self):
        db.init_db()

        self.rooms: Dict[str, RoomInfo] = {
            "lobby": RoomInfo("lobby", "公共讨论大厅", "系统", max_players=999, game_type="rps")
        }
        # 维护 WebSocket -> room_id 的统一反向索引
        self.ws_room_map: Dict[WebSocket, str] = {}
        self.roster: Dict[str, dict] = db.load_all_users()

        self.affairs: List[ClassAffair] = []
        raw_affairs = db.load_all_affairs()
        for a in raw_affairs:
            self.affairs.append(ClassAffair(
                affair_id=a["affair_id"],
                title=a["title"],
                content=a["content"],
                affair_type=a["type"],
                publisher=a["publisher"],
                deadline=a.get("deadline", ""),
                timestamp=a["timestamp"],
                confirmed_students=set(a.get("confirmed_students", []))
            ))

        self.is_global_muted: bool = False
        self.is_screen_locked: bool = False
        self.classroom_title: str = db.get_setting("classroom_title", "数字化课堂 [讨论频道]")

    def set_classroom_title(self, title: str):
        self.classroom_title = title.strip() if title.strip() else "数字化课堂 [讨论频道]"
        db.set_setting("classroom_title", self.classroom_title)

    def register_user(self, name: str, role: str, avatar: str = "") -> dict:
        now_ts = int(time.time() * 1000)
        if name not in self.roster:
            self.roster[name] = {
                "name": name,
                "role": role,
                "avatar": avatar,
                "is_online": True,
                "is_muted": False,
                "last_seen": now_ts,
                "score": 0
            }
        else:
            self.roster[name]["is_online"] = True
            # 若数据库里已经是班长(monitor)，且重连未携带teacher权限，则保留班长身份不被冲掉
            existing_role = self.roster[name].get("role", "student")
            if role == "teacher":
                self.roster[name]["role"] = "teacher"
            elif existing_role == "monitor":
                self.roster[name]["role"] = "monitor"
            else:
                self.roster[name]["role"] = role

            self.roster[name]["last_seen"] = now_ts
            if avatar:
                self.roster[name]["avatar"] = avatar

        db.upsert_user(
            name=name,
            role=self.roster[name]["role"],
            avatar=self.roster[name]["avatar"],
            is_muted=self.roster[name]["is_muted"],
            last_seen=now_ts
        )
        return self.roster[name]

    def set_user_offline(self, name: str):
        if name in self.roster:
            now_ts = int(time.time() * 1000)
            self.roster[name]["is_online"] = False
            self.roster[name]["last_seen"] = now_ts
            db.upsert_user(
                name=name,
                role=self.roster[name]["role"],
                avatar=self.roster[name]["avatar"],
                is_muted=self.roster[name]["is_muted"],
                last_seen=now_ts
            )

    def update_user_mute(self, name: str, is_muted: bool):
        if name in self.roster:
            self.roster[name]["is_muted"] = is_muted
            db.upsert_user(
                name=name,
                role=self.roster[name]["role"],
                avatar=self.roster[name]["avatar"],
                is_muted=is_muted,
                last_seen=self.roster[name]["last_seen"]
            )

    def update_user_role(self, name: str, new_role: str):
        if name in self.roster:
            self.roster[name]["role"] = new_role
            db.upsert_user(
                name=name,
                role=new_role,
                avatar=self.roster[name]["avatar"],
                is_muted=self.roster[name]["is_muted"],
                last_seen=self.roster[name]["last_seen"]
            )

    def rename_student(self, old_name: str, new_name: str):
        if old_name in self.roster and new_name and old_name != new_name:
            rec = self.roster.pop(old_name)
            rec["name"] = new_name
            self.roster[new_name] = rec
            db.rename_user(old_name, new_name)

    def get_roster_list(self) -> List[dict]:
        def sort_key(item):
            role_weight = {"teacher": 0, "monitor": 1, "student": 2}.get(item["role"], 3)
            return (0 if item["is_online"] else 1, role_weight, item["name"])
        return sorted(list(self.roster.values()), key=sort_key)

    def add_affair(self, title: str, content: str, affair_type: str, publisher: str, deadline: str = "") -> ClassAffair:
        now_ts = int(time.time() * 1000)
        affair_id = f"affair_{now_ts}"
        affair = ClassAffair(affair_id, title, content, affair_type, publisher, deadline=deadline, timestamp=now_ts)
        self.affairs.insert(0, affair)
        db.insert_affair(affair_id, title, content, affair_type, publisher, deadline, now_ts)
        return affair

    def edit_affair(self, affair_id: str, title: str, content: str, affair_type: str, deadline: str = "") -> bool:
        for affair in self.affairs:
            if affair.affair_id == affair_id:
                affair.title = title
                affair.content = content
                affair.affair_type = affair_type
                affair.deadline = deadline
                db.update_affair_db(affair_id, title, content, affair_type, deadline)
                return True
        return False

    def delete_affair(self, affair_id: str) -> bool:
        for i, affair in enumerate(self.affairs):
            if affair.affair_id == affair_id:
                self.affairs.pop(i)
                db.delete_affair_db(affair_id)
                return True
        return False

    def confirm_affair(self, affair_id: str, student_name: str) -> bool:
        for affair in self.affairs:
            if affair.affair_id == affair_id:
                affair.confirmed_students.add(student_name)
                db.update_affair_confirmed(affair_id, list(affair.confirmed_students))
                return True
        return False

    # ---------------- 房间状态机核心统一调度 ----------------

    def get_room(self, room_id: str) -> Optional[RoomInfo]:
        return self.rooms.get(room_id)

    def get_room_for_ws(self, ws: WebSocket) -> RoomInfo:
        """动态获取当前 WebSocket 所在的房间，杜绝游离态"""
        r_id = self.ws_room_map.get(ws, "lobby")
        return self.rooms.get(r_id, self.rooms["lobby"])

    def create_room(self, room_id: str, title: str, creator: str, max_players: int = 4, game_type: str = "rps") -> RoomInfo:
        room = RoomInfo(room_id, title, creator, max_players=max_players, game_type=game_type)
        self.rooms[room_id] = room
        return room

    def move_user_to_room(self, ws: WebSocket, target_room_id: str, member_info: dict) -> RoomInfo:
        """原子级切换房间：将客户端从原房间成员移除并挂载到目标房间"""
        old_room_id = self.ws_room_map.get(ws, "lobby")
        if old_room_id in self.rooms:
            self.rooms[old_room_id].members.pop(ws, None)

        target_room = self.rooms.get(target_room_id, self.rooms["lobby"])
        target_room.members[ws] = member_info
        self.ws_room_map[ws] = target_room.room_id
        return target_room

    def disband_room(self, room_id: str, requester: str, is_teacher: bool) -> tuple[bool, Optional[RoomInfo]]:
        """房主或教师解散小组：将原房间全员原子级迁移回大厅，并安全销毁房间"""
        if room_id == "lobby" or room_id not in self.rooms:
            return False, None
        room = self.rooms[room_id]
        if is_teacher or room.creator == requester:
            deleted_room = self.rooms.pop(room_id)
            lobby = self.rooms["lobby"]
            for member_ws, member_info in list(deleted_room.members.items()):
                self.ws_room_map[member_ws] = "lobby"
                lobby.members[member_ws] = member_info
            return True, deleted_room
        return False, None

    def remove_ws(self, ws: WebSocket):
        """断开连接时安全清理映射"""
        r_id = self.ws_room_map.pop(ws, None)
        if r_id and r_id in self.rooms:
            self.rooms[r_id].members.pop(ws, None)

    def get_room_display_list(self) -> List[dict]:
        res = []
        for r_id, r in self.rooms.items():
            if r_id == "lobby":
                continue
            res.append({
                "room_id": r.room_id,
                "title": r.title,
                "creator": r.creator,
                "current_players": r.get_student_count(),
                "max_players": r.max_players,
                "has_teacher": any(m.get("role") == "teacher" for m in r.members.values()),
                "game_type": r.game_type
            })
        return res

manager = RoomManager()