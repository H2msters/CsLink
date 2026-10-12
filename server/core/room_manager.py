import time
from typing import Dict, List, Optional, Set
from fastapi import WebSocket
from games.registry import GameRegistry
from games.base import BaseGame

class ClassAffair:
    """班级事务项：支持公告与作业两种形态"""
    def __init__(self, affair_id: str, title: str, content: str, affair_type: str, publisher: str):
        self.affair_id = affair_id
        self.title = title
        self.content = content
        self.affair_type = affair_type  # notice(公告) / homework(作业)
        self.publisher = publisher
        self.timestamp = int(time.time() * 1000)
        self.confirmed_students: Set[str] = set()  # 点击了“已确认”的学生姓名集合

    def to_dict(self) -> dict:
        return {
            "affair_id": self.affair_id,
            "title": self.title,
            "content": self.content,
            "type": self.affair_type,
            "publisher": self.publisher,
            "timestamp": self.timestamp,
            "confirmed_count": len(self.confirmed_students),
            "confirmed_students": list(self.confirmed_students)
        }

class RoomInfo:
    def __init__(self, room_id: str, title: str, creator: str, game_type: str = "rps"):
        self.room_id = room_id
        self.title = title
        self.creator = creator
        self.game_type = game_type
        self.game: BaseGame = GameRegistry.create_game(game_type)
        self.members: Dict[WebSocket, dict] = {}
        self.history: List[dict] = []

class RoomManager:
    def __init__(self):
        self.rooms: Dict[str, RoomInfo] = {
            "lobby": RoomInfo("lobby", "公共讨论大厅", "系统", game_type="rps")
        }
        
        # 1. 核心：全局花名册字典 { "用户名/学号": { "role", "avatar", "is_online", "is_muted", "last_seen" } }
        self.roster: Dict[str, dict] = {}
        
        # 2. 班级事务列表
        self.affairs: List[ClassAffair] = []
        
        # 3. 全局全员禁言开关
        self.is_global_muted: bool = False

    def register_user(self, name: str, role: str, avatar: str = "") -> dict:
        """登记或更新学生信息并标记为在线"""
        if name not in self.roster:
            self.roster[name] = {
                "name": name,
                "role": role,
                "avatar": avatar,
                "is_online": True,
                "is_muted": False,
                "last_seen": int(time.time() * 1000)
            }
        else:
            self.roster[name]["is_online"] = True
            self.roster[name]["last_seen"] = int(time.time() * 1000)
            if avatar:
                self.roster[name]["avatar"] = avatar
        return self.roster[name]

    def set_user_offline(self, name: str):
        """用户断开连接时，保留记录，仅标记为离线"""
        if name in self.roster:
            self.roster[name]["is_online"] = False
            self.roster[name]["last_seen"] = int(time.time() * 1000)

    def get_roster_list(self) -> List[dict]:
        """按在线状态优先、角色优先级排序输出花名册"""
        def sort_key(item):
            role_weight = {"teacher": 0, "monitor": 1, "student": 2}.get(item["role"], 3)
            return (0 if item["is_online"] else 1, role_weight, item["name"])
        return sorted(list(self.roster.values()), key=sort_key)

    def add_affair(self, title: str, content: str, affair_type: str, publisher: str) -> ClassAffair:
        affair_id = f"affair_{int(time.time())}"
        affair = ClassAffair(affair_id, title, content, affair_type, publisher)
        self.affairs.insert(0, affair)
        return affair

    def confirm_affair(self, affair_id: str, student_name: str) -> bool:
        for affair in self.affairs:
            if affair.affair_id == affair_id:
                affair.confirmed_students.add(student_name)
                return True
        return False

    def get_room(self, room_id: str) -> Optional[RoomInfo]:
        return self.rooms.get(room_id)

    def create_room(self, room_id: str, title: str, creator: str, game_type: str = "rps") -> RoomInfo:
        room = RoomInfo(room_id, title, creator, game_type)
        self.rooms[room_id] = room
        return room

manager = RoomManager()