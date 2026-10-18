from fastapi import APIRouter, WebSocket, WebSocketDisconnect, Query
from core.room_manager import manager
import json
import time
import random

router = APIRouter()
TEACHER_AUTH_TOKEN = "teacher888"

# 全局抢答状态机
class BuzzManager:
    def __init__(self):
        self.is_active = False
        self.title = ""
        self.start_timestamp_ms = 0
        self.winner = None
        self.cost_ms = 0

    def start(self, title: str):
        self.is_active = True
        self.title = title if title.strip() else "随堂即时抢答"
        self.start_timestamp_ms = int(time.time() * 1000)
        self.winner = None
        self.cost_ms = 0

    def submit(self, student_name: str) -> bool:
        if self.is_active and not self.winner:
            now_ms = int(time.time() * 1000)
            self.winner = student_name
            self.cost_ms = max(1, now_ms - self.start_timestamp_ms)
            self.is_active = False
            return True
        return False

    def stop(self):
        self.is_active = False

buzz_session = BuzzManager()

@router.websocket("/ws/chat")
async def websocket_chat_endpoint(
    websocket: WebSocket,
    name: str = Query(default=""),
    role: str = Query(default="student"),
    token: str = Query(default=""),
    room: str = Query(default="lobby")
):
    await websocket.accept()

    # 1. 物理来源与权限鉴定
    client_ip = websocket.client.host if websocket.client else "127.0.0.1"
    if client_ip in ("127.0.0.1", "localhost", "::1"):
        actual_role = "teacher"
    elif token == TEACHER_AUTH_TOKEN and role in ("teacher", "monitor"):
        actual_role = role
    else:
        actual_role = "student"

    actual_name = name.strip() if name and name.strip() else f"同学_{client_ip.split('.')[-1]}"
    target_room_id = room.strip() if room and room.strip() else "lobby"

    # 登记入全局花名册
    user_record = manager.register_user(actual_name, actual_role)

    current_room = manager.get_room(target_room_id)
    if not current_room:
        current_room = manager.create_room(target_room_id, f"房间_{target_room_id}", actual_name)

    current_room.members[websocket] = {
        "name": actual_name,
        "role": actual_role,
        "avatar": user_record.get("avatar", ""),
        "ip": client_ip
    }

    # 2. 下发初始全量状态包
    init_payload = {
        "type": "init_classroom_state",
        "room_id": current_room.room_id,
        "my_verified_role": actual_role,
        "is_global_muted": manager.is_global_muted,
        "buzz_active": buzz_session.is_active,
        "messages": current_room.history,
        "roster": manager.get_roster_list(),
        "affairs": [a.to_dict() for a in manager.affairs],
        "timestamp": int(time.time() * 1000)
    }
    await websocket.send_text(json.dumps(init_payload, ensure_ascii=False))
    await broadcast_roster_update()

    try:
        while True:
            raw_text = await websocket.receive_text()
            try:
                data = json.loads(raw_text)
            except Exception:
                data = {"type": "chat", "content": raw_text}

            msg_type = data.get("type", "chat")
            sender_record = manager.roster.get(actual_name, {})
            current_user_role = sender_record.get("role", actual_role)

            # ---------------- 教师管控指令 ----------------
            if current_user_role == "teacher":
                # 全员禁言
                if msg_type == "set_global_mute":
                    manager.is_global_muted = data.get("is_muted", False)
                    await broadcast_to_all({
                        "type": "global_mute_state",
                        "is_muted": manager.is_global_muted,
                        "timestamp": int(time.time() * 1000)
                    })
                    continue

                # 发起抢答
                elif msg_type == "start_buzz":
                    buzz_title = data.get("title", "快速抢答环节")
                    buzz_session.start(buzz_title)
                    await broadcast_to_all({
                        "type": "buzz_started",
                        "title": buzz_session.title,
                        "timestamp": buzz_session.start_timestamp_ms
                    })
                    continue

                # 终止抢答
                elif msg_type == "stop_buzz":
                    buzz_session.stop()
                    await broadcast_to_all({
                        "type": "buzz_stopped",
                        "timestamp": int(time.time() * 1000)
                    })
                    continue

                # 新增：随机点名（天选之子）
                elif msg_type == "random_pick":
                    # 优先从当前在线的学生/班长中抽取
                    online_students = [
                        u["name"] for u in manager.roster.values()
                        if u.get("is_online") and u.get("role") != "teacher"
                    ]
                    # 若当前无在线学生（如老师单机调试），则从所有非教师名册或全员中兜底抽取
                    if not online_students:
                        online_students = [
                            u["name"] for u in manager.roster.values()
                            if u.get("role") != "teacher"
                        ]
                    if not online_students:
                        online_students = list(manager.roster.keys())

                    winner_name = random.choice(online_students) if online_students else "暂无学生"

                    # 广播点名动画数据包
                    pick_payload = {
                        "type": "random_pick_result",
                        "winner": winner_name,
                        "candidates": online_students,
                        "timestamp": int(time.time() * 1000)
                    }
                    await broadcast_to_all(pick_payload)

                    # 同步写入公屏系统消息
                    notice_msg = {
                        "type": "system",
                        "sender": "系统",
                        "role": "teacher",
                        "avatar": "",
                        "content": f"随机点名结果揭晓：请【{winner_name}】同学回答问题！",
                        "timestamp": int(time.time() * 1000)
                    }
                    current_room.history.append(notice_msg)
                    if len(current_room.history) > 50:
                        current_room.history.pop(0)
                    await broadcast_to_all(notice_msg)
                    continue

                # 单人管理与事务发布
                elif msg_type == "admin_manage_student":
                    target_student = data.get("target_name")
                    operation = data.get("operation")
                    if target_student in manager.roster:
                        if operation == "toggle_mute":
                            manager.roster[target_student]["is_muted"] = not manager.roster[target_student]["is_muted"]
                        elif operation == "set_role":
                            manager.roster[target_student]["role"] = data.get("new_role", "student")
                        elif operation == "rename":
                            new_name = data.get("new_name", target_student).strip()
                            if new_name and new_name != target_student:
                                rec = manager.roster.pop(target_student)
                                rec["name"] = new_name
                                manager.roster[new_name] = rec
                        await broadcast_roster_update()
                    continue

                elif msg_type == "publish_affair":
                    new_affair = manager.add_affair(
                        data.get("title", ""),
                        data.get("content", ""),
                        data.get("affair_type", "notice"),
                        actual_name
                    )
                    await broadcast_to_all({
                        "type": "affair_update",
                        "affairs": [a.to_dict() for a in manager.affairs],
                        "new_item": new_affair.to_dict(),
                        "timestamp": int(time.time() * 1000)
                    })
                    continue

            # ---------------- 学生提交抢答 ----------------
            if msg_type == "submit_buzz":
                if buzz_session.submit(actual_name):
                    result_payload = {
                        "type": "buzz_result",
                        "winner": buzz_session.winner,
                        "cost_ms": buzz_session.cost_ms,
                        "title": buzz_session.title,
                        "timestamp": int(time.time() * 1000)
                    }
                    await broadcast_to_all(result_payload)

                    notice_msg = {
                        "type": "system",
                        "sender": "系统",
                        "role": "teacher",
                        "avatar": "",
                        "content": f"🎉 恭喜【{buzz_session.winner}】率先抢答成功！耗时仅 {buzz_session.cost_ms} ms",
                        "timestamp": int(time.time() * 1000)
                    }
                    current_room.history.append(notice_msg)
                    if len(current_room.history) > 50:
                        current_room.history.pop(0)
                    await broadcast_to_all(notice_msg)
                continue

            # ---------------- 学生确认事务 ----------------
            if msg_type == "confirm_affair":
                if manager.confirm_affair(data.get("affair_id"), actual_name):
                    await broadcast_to_all({
                        "type": "affair_update",
                        "affairs": [a.to_dict() for a in manager.affairs],
                        "timestamp": int(time.time() * 1000)
                    })
                continue

            # ---------------- 资料更新 ----------------
            if msg_type == "set_name":
                new_name = data.get("username", actual_name).strip()
                new_avatar = data.get("avatar", "")
                if new_name:
                    if new_name != actual_name:
                        old_data = manager.roster.pop(actual_name, {})
                        old_data["name"] = new_name
                        old_data["avatar"] = new_avatar
                        manager.roster[new_name] = old_data
                        actual_name = new_name
                    else:
                        manager.roster[actual_name]["avatar"] = new_avatar
                    current_room.members[websocket]["name"] = actual_name
                    await broadcast_roster_update()
                continue

            # ---------------- 讨论消息（受禁言管控） ----------------
            if msg_type in ("chat", "image"):
                if (manager.is_global_muted and current_user_role != "teacher") or sender_record.get("is_muted", False):
                    await websocket.send_text(json.dumps({
                        "type": "system",
                        "content": "【发言拦截】您当前处于禁言状态或全员禁言已开启",
                        "timestamp": int(time.time() * 1000)
                    }, ensure_ascii=False))
                    continue

                chat_item = {
                    "type": msg_type,
                    "sender": actual_name,
                    "role": current_user_role,
                    "avatar": data.get("avatar") or sender_record.get("avatar", ""),
                    "content": data.get("content", ""),
                    "timestamp": int(time.time() * 1000)
                }
                current_room.history.append(chat_item)
                if len(current_room.history) > 50:
                    current_room.history.pop(0)
                await broadcast_to_all(chat_item)

    except WebSocketDisconnect:
        manager.set_user_offline(actual_name)
        current_room.members.pop(websocket, None)
        await broadcast_roster_update()
    except Exception:
        manager.set_user_offline(actual_name)
        current_room.members.pop(websocket, None)
        await broadcast_roster_update()

async def broadcast_roster_update():
    await broadcast_to_all({
        "type": "roster_update",
        "roster": manager.get_roster_list(),
        "timestamp": int(time.time() * 1000)
    })

async def broadcast_to_all(payload: dict):
    raw = json.dumps(payload, ensure_ascii=False)
    for room in manager.rooms.values():
        for ws in list(room.members.keys()):
            try:
                await ws.send_text(raw)
            except Exception:
                pass