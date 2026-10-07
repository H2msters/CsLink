from fastapi import FastAPI, WebSocket, WebSocketDisconnect, Query
from typing import Dict, List, Optional
import uvicorn
import json
import time

app = FastAPI(title="CampusChat Community & Game Server")

class RPSGame:
    def __init__(self):
        # 记录各玩家的选择: { "玩家名": "石头" / "剪刀" / "布" }
        self.choices: Dict[str, str] = {}
        self.result_text: str = ""

    def reset(self):
        self.choices.clear()
        self.result_text = ""

    def make_choice(self, player: str, choice: str) -> bool:
        self.choices[player] = choice
        # 两人都出拳，触发结算
        return len(self.choices) >= 2

    def judge(self) -> dict:
        players = list(self.choices.keys())
        p1, p2 = players[0], players[1]
        c1, c2 = self.choices[p1], self.choices[p2]

        winner = ""
        if c1 == c2:
            winner = "draw"
            self.result_text = f"平局！双方都出了【{c1}】"
        elif (c1 == "石头" and c2 == "剪刀") or \
             (c1 == "剪刀" and c2 == "布") or \
             (c1 == "布" and c2 == "石头"):
            winner = p1
            self.result_text = f"【{p1}】获胜！({c1} 胜 {c2})"
        else:
            winner = p2
            self.result_text = f"【{p2}】获胜！({c2} 胜 {c1})"

        return {
            "p1_name": p1,
            "p1_choice": c1,
            "p2_name": p2,
            "p2_choice": c2,
            "winner": winner,
            "result_text": self.result_text
        }

class RoomInfo:
    def __init__(self, room_id: str, title: str, creator: str, max_players: int = 2):
        self.room_id = room_id
        self.title = title
        self.creator = creator
        self.max_players = max_players
        self.game = RPSGame()

    def to_dict(self, member_count: int) -> dict:
        return {
            "room_id": self.room_id,
            "title": self.title,
            "creator": self.creator,
            "max_players": self.max_players,
            "current_players": member_count
        }

class CommunityRoomManager:
    def __init__(self):
        self.rooms: Dict[str, Dict[WebSocket, dict]] = {"lobby": {}}
        self.room_metadata: Dict[str, RoomInfo] = {
            "lobby": RoomInfo("lobby", "校园公共大厅", "系统", max_players=999)
        }
        self.room_histories: Dict[str, List[dict]] = {"lobby": []}
        self.max_history: int = 50

    async def connect(self, websocket: WebSocket, room_id: str, username: str, avatar: str):
        await websocket.accept()
        if room_id not in self.rooms:
            self.rooms[room_id] = {}
            self.room_histories[room_id] = []
            if room_id not in self.room_metadata:
                self.room_metadata[room_id] = RoomInfo(room_id, f"房间_{room_id}", username)

        self.rooms[room_id][websocket] = {"name": username, "avatar": avatar}

        # 仅下发当前房间的历史记录
        user_avatars = {
            info["name"]: info.get("avatar", "")
            for info in self.rooms[room_id].values()
            if info.get("avatar")
        }
        history_payload = {
            "type": "history",
            "room_id": room_id,
            "messages": self.room_histories.get(room_id, []),
            "user_avatars": user_avatars,
            "timestamp": int(time.time() * 1000)
        }
        await websocket.send_text(json.dumps(history_payload, ensure_ascii=False))

        # 独立房间发送即时进房提示（不入历史记录）
        if room_id != "lobby":
            join_notice = {
                "type": "system",
                "sender": "系统",
                "content": f"{username} 进入了房间",
                "timestamp": int(time.time() * 1000)
            }
            await self.broadcast_to_room(room_id, join_notice)

        await self.broadcast_room_state(room_id)
        await self.broadcast_room_list()

    def disconnect(self, websocket: WebSocket) -> tuple[str, str]:
        for room_id, members in list(self.rooms.items()):
            if websocket in members:
                user_info = members.pop(websocket, {})
                user_name = user_info.get("name", "未知用户")
                
                if room_id != "lobby" and room_id in self.room_metadata:
                    meta = self.room_metadata[room_id]
                    if not members:
                        self.rooms.pop(room_id, None)
                        self.room_histories.pop(room_id, None)
                        self.room_metadata.pop(room_id, None)
                    elif meta.creator == user_name:
                        new_host = next(iter(members.values()))["name"]
                        meta.creator = new_host

                return room_id, user_name
        return "", "未知用户"

    def get_user_room(self, websocket: WebSocket) -> str:
        for room_id, members in self.rooms.items():
            if websocket in members:
                return room_id
        return "lobby"

    def create_room(self, room_id: str, title: str, creator: str, max_players: int = 2):
        self.room_metadata[room_id] = RoomInfo(room_id, title, creator, max_players)
        self.rooms[room_id] = {}
        self.room_histories[room_id] = []

    def record_message(self, room_id: str, payload: dict):
        if room_id not in self.room_histories:
            self.room_histories[room_id] = []
        self.room_histories[room_id].append(payload)
        if len(self.room_histories[room_id]) > self.max_history:
            self.room_histories[room_id].pop(0)

    async def broadcast_to_room(self, room_id: str, payload: dict):
        if room_id not in self.rooms:
            return
        raw_text = json.dumps(payload, ensure_ascii=False)
        for connection in list(self.rooms[room_id].keys()):
            try:
                await connection.send_text(raw_text)
            except Exception:
                pass

    async def broadcast_room_state(self, room_id: str):
        if room_id not in self.rooms:
            return
        members = [info["name"] for info in self.rooms[room_id].values()]
        meta = self.room_metadata.get(room_id)
        
        # 猜拳准备/出拳状态（不泄露出的具体是什么，只告诉对方是否已出拳）
        choices_status = {}
        if meta and meta.game:
            for m in members:
                choices_status[m] = (m in meta.game.choices)

        payload = {
            "type": "room_state",
            "room_id": room_id,
            "title": meta.title if meta else room_id,
            "creator": meta.creator if meta else "",
            "members": members,
            "choices_status": choices_status,
            "count": len(members),
            "timestamp": int(time.time() * 1000)
        }
        await self.broadcast_to_room(room_id, payload)

    async def broadcast_room_list(self):
        room_list = []
        for r_id, meta in self.room_metadata.items():
            if r_id != "lobby":
                member_count = len(self.rooms.get(r_id, {}))
                room_list.append(meta.to_dict(member_count))

        payload = {
            "type": "room_list",
            "rooms": room_list,
            "timestamp": int(time.time() * 1000)
        }
        await self.broadcast_to_room("lobby", payload)

manager = CommunityRoomManager()

@app.websocket("/ws/chat")
async def websocket_community_endpoint(
    websocket: WebSocket, 
    name: str = Query(default=""),
    avatar: str = Query(default=""),
    room: str = Query(default="lobby")
):
    client_ip = websocket.client.host if websocket.client else "匿名节点"
    actual_name = name.strip() if name and name.strip() else f"同学_{client_ip.split('.')[-1]}"
    target_room = room.strip() if room and room.strip() else "lobby"
    
    await manager.connect(websocket, target_room, actual_name, avatar)

    if target_room == "lobby":
        await manager.broadcast_room_list()

    try:
        while True:
            raw_data = await websocket.receive_text()
            try:
                msg_json = json.loads(raw_data)
            except Exception:
                msg_json = {"type": "chat", "content": raw_data}

            msg_type = msg_json.get("type", "chat")
            current_room = manager.get_user_room(websocket)

            # 1. 创建独立房间
            if msg_type == "create_room":
                title = msg_json.get("title", f"{actual_name}的猜拳房间").strip()
                new_room_id = f"room_{int(time.time())}"
                manager.create_room(new_room_id, title, actual_name, max_players=2)

                old_info = manager.rooms[current_room].pop(websocket, {})
                await manager.broadcast_room_state(current_room)
                await manager.broadcast_room_list()

                # 通知客户端跳进新房间
                confirm_payload = {
                    "type": "enter_room_success",
                    "room_id": new_room_id,
                    "title": title
                }
                await websocket.send_text(json.dumps(confirm_payload, ensure_ascii=False))
                await manager.connect(websocket, new_room_id, old_info.get("name", actual_name), old_info.get("avatar", ""))

            # 2. 加入指定房间
            elif msg_type == "join_room":
                target_room_id = msg_json.get("room_id", "lobby").strip()
                if target_room_id in manager.room_metadata and target_room_id != current_room:
                    meta = manager.room_metadata[target_room_id]
                    if len(manager.rooms.get(target_room_id, {})) >= meta.max_players:
                        err_payload = {"type": "system", "sender": "系统", "content": "房间人数已满（最多2人）"}
                        await websocket.send_text(json.dumps(err_payload, ensure_ascii=False))
                        continue

                    old_info = manager.rooms[current_room].pop(websocket, {})
                    await manager.broadcast_room_state(current_room)
                    await manager.broadcast_room_list()

                    confirm_payload = {
                        "type": "enter_room_success",
                        "room_id": target_room_id,
                        "title": meta.title
                    }
                    await websocket.send_text(json.dumps(confirm_payload, ensure_ascii=False))
                    await manager.connect(websocket, target_room_id, old_info.get("name", actual_name), old_info.get("avatar", ""))

            # 3. 退出房间返回大厅
            elif msg_type == "leave_room":
                if current_room != "lobby":
                    old_info = manager.rooms[current_room].pop(websocket, {})
                    meta = manager.room_metadata.get(current_room)
                    if meta:
                        meta.game.reset()
                        if not manager.rooms[current_room]:
                            manager.rooms.pop(current_room, None)
                            manager.room_histories.pop(current_room, None)
                            manager.room_metadata.pop(current_room, None)
                    
                    leave_msg = {
                        "type": "system",
                        "sender": "系统",
                        "content": f"{actual_name} 退出了房间",
                        "timestamp": int(time.time() * 1000)
                    }
                    await manager.broadcast_to_room(current_room, leave_msg)
                    await manager.broadcast_room_state(current_room)
                    await manager.broadcast_room_list()

                    await manager.connect(websocket, "lobby", old_info.get("name", actual_name), old_info.get("avatar", ""))

            # 4. 猜拳对决出拳
            elif msg_type == "rps_choice":
                if current_room != "lobby" and current_room in manager.room_metadata:
                    choice = msg_json.get("choice", "")
                    meta = manager.room_metadata[current_room]
                    both_ready = meta.game.make_choice(actual_name, choice)

                    # 广播当前出拳进度（已出拳/未出拳）
                    await manager.broadcast_room_state(current_room)

                    # 双方都出拳，揭晓结果
                    if both_ready:
                        result_info = meta.game.judge()
                        result_payload = {
                            "type": "rps_result",
                            **result_info,
                            "timestamp": int(time.time() * 1000)
                        }
                        await manager.broadcast_to_room(current_room, result_payload)

            # 5. 猜拳重置（再来一局）
            elif msg_type == "rps_reset":
                if current_room != "lobby" and current_room in manager.room_metadata:
                    meta = manager.room_metadata[current_room]
                    meta.game.reset()
                    reset_payload = {
                        "type": "rps_reset_notice",
                        "content": "对局已重置，请重新出拳",
                        "timestamp": int(time.time() * 1000)
                    }
                    await manager.broadcast_to_room(current_room, reset_payload)
                    await manager.broadcast_room_state(current_room)

            # 6. 改名与头像更新
            elif msg_type == "set_name":
                new_name = msg_json.get("username", "").strip()
                new_avatar = msg_json.get("avatar", "")
                if new_name:
                    if websocket in manager.rooms[current_room]:
                        manager.rooms[current_room][websocket] = {"name": new_name, "avatar": new_avatar}
                        actual_name = new_name

                        avatar_update_payload = {
                            "type": "avatar_update",
                            "username": new_name,
                            "avatar": new_avatar
                        }
                        await manager.broadcast_to_room(current_room, avatar_update_payload)
                        await manager.broadcast_room_state(current_room)

            # 7. 聊天发言与图片
            elif msg_type in ("chat", "image"):
                user_info = manager.rooms[current_room].get(websocket, {})
                current_sender = user_info.get("name", actual_name)
                current_avatar = msg_json.get("avatar") or user_info.get("avatar", "")
                content = msg_json.get("content", "")

                chat_payload = {
                    "type": msg_type,
                    "sender": current_sender,
                    "content": content,
                    "avatar": current_avatar,
                    "timestamp": int(time.time() * 1000)
                }
                manager.record_message(current_room, chat_payload)
                await manager.broadcast_to_room(current_room, chat_payload)

    except WebSocketDisconnect:
        r_id, left_user = manager.disconnect(websocket)
        if r_id and r_id != "lobby":
            leave_notice = {
                "type": "system",
                "sender": "系统",
                "content": f"{left_user} 离开了房间",
                "timestamp": int(time.time() * 1000)
            }
            await manager.broadcast_to_room(r_id, leave_notice)
            await manager.broadcast_room_state(r_id)
            await manager.broadcast_room_list()
    except Exception:
        manager.disconnect(websocket)

if __name__ == "__main__":
    uvicorn.run("main:app", host="0.0.0.0", port=8000, reload=True)