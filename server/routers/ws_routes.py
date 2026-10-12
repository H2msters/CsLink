from fastapi import APIRouter, WebSocket, WebSocketDisconnect, Query
from core.room_manager import manager
import json
import time

router = APIRouter()
TEACHER_AUTH_TOKEN = "teacher888"

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

    # 登记入全局花名册并标为在线
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

    # 2. 下发初始全量状态包（历史记录、花名册、事务列表、禁言状态）
    init_payload = {
        "type": "init_classroom_state",
        "room_id": current_room.room_id,
        "my_verified_role": actual_role,
        "is_global_muted": manager.is_global_muted,
        "messages": current_room.history,
        "roster": manager.get_roster_list(),
        "affairs": [a.to_dict() for a in manager.affairs],
        "timestamp": int(time.time() * 1000)
    }
    await websocket.send_text(json.dumps(init_payload, ensure_ascii=False))

    # 广播全员花名册更新
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

            # ---------------- 教师专属管理指令 ----------------
            if current_user_role == "teacher":
                # A. 教师设置全员禁言
                if msg_type == "set_global_mute":
                    manager.is_global_muted = data.get("is_muted", False)
                    mute_broadcast = {
                        "type": "global_mute_state",
                        "is_muted": manager.is_global_muted,
                        "timestamp": int(time.time() * 1000)
                    }
                    await broadcast_to_all(mute_broadcast)
                    continue

                # B. 教师操作单名学生（单独禁言/解禁、更改角色、修改姓名）
                elif msg_type == "admin_manage_student":
                    target_student = data.get("target_name")
                    operation = data.get("operation")  # toggle_mute / set_role / rename
                    
                    if target_student in manager.roster:
                        if operation == "toggle_mute":
                            manager.roster[target_student]["is_muted"] = not manager.roster[target_student]["is_muted"]
                        elif operation == "set_role":
                            new_role = data.get("new_role", "student")
                            manager.roster[target_student]["role"] = new_role
                        elif operation == "rename":
                            new_name = data.get("new_name", target_student).strip()
                            if new_name and new_name != target_student:
                                rec = manager.roster.pop(target_student)
                                rec["name"] = new_name
                                manager.roster[new_name] = rec

                        await broadcast_roster_update()
                    continue

                # C. 教师发布班级事务（公告 / 作业）
                elif msg_type == "publish_affair":
                    a_title = data.get("title", "")
                    a_content = data.get("content", "")
                    a_type = data.get("affair_type", "notice")  # notice / homework
                    new_affair = manager.add_affair(a_title, a_content, a_type, actual_name)
                    
                    affair_broadcast = {
                        "type": "affair_update",
                        "affairs": [a.to_dict() for a in manager.affairs],
                        "new_item": new_affair.to_dict(),
                        "timestamp": int(time.time() * 1000)
                    }
                    await broadcast_to_all(affair_broadcast)
                    continue

            # ---------------- 学生确认事务（已阅/完成） ----------------
            if msg_type == "confirm_affair":
                affair_id = data.get("affair_id")
                if manager.confirm_affair(affair_id, actual_name):
                    affair_sync = {
                        "type": "affair_update",
                        "affairs": [a.to_dict() for a in manager.affairs],
                        "timestamp": int(time.time() * 1000)
                    }
                    await broadcast_to_all(affair_sync)
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

            # ---------------- 常规讨论消息（受禁言管控） ----------------
            if msg_type in ("chat", "image"):
                # 如果开启了全员禁言且不是教师，或该学生被单独禁言，直接拦截
                if (manager.is_global_muted and current_user_role != "teacher") or sender_record.get("is_muted", False):
                    refuse_payload = {
                        "type": "system",
                        "content": "【发言拦截】您当前处于禁言状态或全员禁言已开启",
                        "timestamp": int(time.time() * 1000)
                    }
                    await websocket.send_text(json.dumps(refuse_payload, ensure_ascii=False))
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
    payload = {
        "type": "roster_update",
        "roster": manager.get_roster_list(),
        "timestamp": int(time.time() * 1000)
    }
    await broadcast_to_all(payload)

async def broadcast_to_all(payload: dict):
    raw = json.dumps(payload, ensure_ascii=False)
    for room in manager.rooms.values():
        for ws in list(room.members.keys()):
            try:
                await ws.send_text(raw)
            except Exception:
                pass