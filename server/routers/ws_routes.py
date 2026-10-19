from fastapi import APIRouter, WebSocket, WebSocketDisconnect, Query
from core.room_manager import manager, RoomInfo
from core import db
import json
import time
import random

router = APIRouter()
TEACHER_AUTH_TOKEN = "teacher888"

class BuzzManager:
    def __init__(self):
        self.is_active = False
        self.title = ""
        self.start_timestamp_ms = 0
        self.winner = None
        self.cost_ms = 0

    def start(self, title: str):
        self.is_active = True
        self.title = title.strip() if title.strip() else "随堂即时抢答"
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

    def get_state_payload(self) -> dict:
        return {
            "type": "buzz_state",
            "is_active": self.is_active,
            "title": self.title,
            "winner": self.winner,
            "cost_ms": self.cost_ms,
            "timestamp": int(time.time() * 1000)
        }

class VoteManager:
    def __init__(self):
        self.is_active = False
        self.title = "随堂单选测验"
        self.votes = {}

    def start(self, title: str):
        self.is_active = True
        self.title = title.strip() if title and title.strip() else "随堂单选测验"
        self.votes.clear()

    def submit(self, student_name: str, option: str) -> bool:
        opt = option.strip().upper()
        if self.is_active and opt in ("A", "B", "C", "D"):
            self.votes[student_name] = opt
            return True
        return False

    def stop(self):
        self.is_active = False

    def get_state_payload(self) -> dict:
        counts = {"A": 0, "B": 0, "C": 0, "D": 0}
        for chosen in self.votes.values():
            if chosen in counts:
                counts[chosen] += 1
        return {
            "type": "vote_state",
            "is_active": self.is_active,
            "title": self.title,
            "counts": counts,
            "total": len(self.votes),
            "voters": self.votes,
            "timestamp": int(time.time() * 1000)
        }

class PickManager:
    def __init__(self):
        self.picked_history = set()

    def pick_next(self, candidates: list) -> tuple:
        if not candidates:
            return "暂无学生", False, 0

        available = [s for s in candidates if s not in self.picked_history]
        is_new_round = False

        if not available:
            self.picked_history.clear()
            available = list(candidates)
            is_new_round = True

        winner = random.choice(available)
        self.picked_history.add(winner)
        remaining = len(available) - 1
        return winner, is_new_round, remaining

buzz_session = BuzzManager()
vote_session = VoteManager()
pick_session = PickManager()

@router.websocket("/ws/chat")
async def websocket_chat_endpoint(
    websocket: WebSocket,
    name: str = Query(default=""),
    role: str = Query(default="student"),
    token: str = Query(default=""),
    room: str = Query(default="lobby")
):
    await websocket.accept()

    client_ip = websocket.client.host if websocket.client else "127.0.0.1"
    if token == TEACHER_AUTH_TOKEN and role in ("teacher", "monitor"):
        actual_role = role
    else:
        actual_role = "student"

    actual_name = name.strip() if name and name.strip() else f"同学_{client_ip.split('.')[-1]}"
    target_room_id = room.strip() if room and room.strip() else "lobby"

    user_record = manager.register_user(actual_name, actual_role)
    member_info = {
        "name": actual_name,
        "role": actual_role,
        "avatar": user_record.get("avatar", ""),
        "ip": client_ip
    }

    current_room = manager.move_user_to_room(websocket, target_room_id, member_info)

    # 初始全量数据包
    init_payload = {
        "type": "init_classroom_state",
        "room_id": current_room.room_id,
        "classroom_title": manager.classroom_title,
        "my_verified_role": actual_role,
        "is_global_muted": manager.is_global_muted,
        "buzz_active": buzz_session.is_active,
        "buzz_data": buzz_session.get_state_payload(),
        "vote_active": vote_session.is_active,
        "vote_data": vote_session.get_state_payload(),
        "messages": current_room.history,
        "rooms": manager.get_room_display_list(),
        "roster": manager.get_roster_list(),
        "affairs": [a.to_dict() for a in manager.affairs],
        "timestamp": int(time.time() * 1000)
    }
    await websocket.send_text(json.dumps(init_payload, ensure_ascii=False))
    await broadcast_roster_update()
    await broadcast_room_list_update()

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
            # 动态寻址：获取当前用户实时所在的房间，彻底避免局部变量与多协程脱节
            current_room = manager.get_room_for_ws(websocket)

            # ---------------- 客户端返回大厅时的即时刷新指令 ----------------
            if msg_type == "refresh_lobby":
                await websocket.send_text(json.dumps({
                    "type": "room_list",
                    "rooms": manager.get_room_display_list(),
                    "timestamp": int(time.time() * 1000)
                }, ensure_ascii=False))
                await websocket.send_text(json.dumps({
                    "type": "roster_update",
                    "roster": manager.get_roster_list(),
                    "timestamp": int(time.time() * 1000)
                }, ensure_ascii=False))
                continue

            # ---------------- 小组协作房间指令 ----------------
            if msg_type == "create_room":
                r_title = data.get("title", f"{actual_name}的协作组").strip()
                max_p = int(data.get("max_players", 4))
                new_room_id = f"group_{int(time.time() * 1000)}"

                created_room = manager.create_room(
                    new_room_id,
                    r_title,
                    actual_name,
                    max_players=max_p,
                    game_type="rps"
                )

                # 将用户移入新组
                current_room = manager.move_user_to_room(websocket, created_room.room_id, {
                    "name": actual_name,
                    "role": current_user_role,
                    "avatar": sender_record.get("avatar", ""),
                    "ip": client_ip
                })

                await websocket.send_text(json.dumps({
                    "type": "enter_room_success",
                    "room_id": created_room.room_id,
                    "title": created_room.title,
                    "max_players": created_room.max_players,
                    "creator": created_room.creator,
                    "role": current_user_role,
                    "timestamp": int(time.time() * 1000)
                }, ensure_ascii=False))

                await websocket.send_text(json.dumps({
                    "type": "history",
                    "messages": created_room.history,
                    "user_avatars": {m["name"]: m["avatar"] for m in created_room.members.values()},
                    "timestamp": int(time.time() * 1000)
                }, ensure_ascii=False))

                await broadcast_to_room(created_room, {
                    "type": "room_state",
                    "members": [m["name"] for m in created_room.members.values()],
                    "count": created_room.get_student_count(),
                    "max_players": created_room.max_players,
                    "creator": created_room.creator,
                    "choices_status": created_room.game.get_state().get("choices_status", {})
                })

                await broadcast_room_list_update()
                continue

            elif msg_type == "join_room":
                target_id = data.get("room_id")
                target_room = manager.get_room(target_id)
                if not target_room:
                    await websocket.send_text(json.dumps({
                        "type": "system",
                        "content": "[加入失败] 该协作小组已解散或不存在",
                        "timestamp": int(time.time() * 1000)
                    }, ensure_ascii=False))
                    continue

                is_teacher = (current_user_role == "teacher")
                if target_room.is_full(is_teacher=is_teacher):
                    await websocket.send_text(json.dumps({
                        "type": "system",
                        "content": f"[加入失败] 该小组已满员 ({target_room.max_players}/{target_room.max_players} 人)",
                        "timestamp": int(time.time() * 1000)
                    }, ensure_ascii=False))
                    continue

                current_room = manager.move_user_to_room(websocket, target_id, {
                    "name": actual_name,
                    "role": current_user_role,
                    "avatar": sender_record.get("avatar", ""),
                    "ip": client_ip
                })

                await websocket.send_text(json.dumps({
                    "type": "enter_room_success",
                    "room_id": target_room.room_id,
                    "title": target_room.title,
                    "max_players": target_room.max_players,
                    "creator": target_room.creator,
                    "role": current_user_role,
                    "timestamp": int(time.time() * 1000)
                }, ensure_ascii=False))

                join_notice = {
                    "type": "system",
                    "sender": "系统",
                    "role": current_user_role,
                    "avatar": "",
                    "content": f"{'【教师视察】' if is_teacher else ''}【{actual_name}】进入了小组",
                    "timestamp": int(time.time() * 1000)
                }
                current_room.add_message(join_notice)
                await broadcast_to_room(current_room, join_notice)

                await websocket.send_text(json.dumps({
                    "type": "history",
                    "messages": current_room.history,
                    "user_avatars": {m["name"]: m["avatar"] for m in current_room.members.values()},
                    "timestamp": int(time.time() * 1000)
                }, ensure_ascii=False))

                await broadcast_to_room(current_room, {
                    "type": "room_state",
                    "members": [m["name"] for m in current_room.members.values()],
                    "count": current_room.get_student_count(),
                    "max_players": current_room.max_players,
                    "creator": current_room.creator,
                    "choices_status": current_room.game.get_state().get("choices_status", {})
                })

                await broadcast_room_list_update()
                continue

            elif msg_type == "leave_room":
                old_room = manager.get_room_for_ws(websocket)
                if old_room.room_id != "lobby":
                    leave_notice = {
                        "type": "system",
                        "sender": "系统",
                        "role": current_user_role,
                        "avatar": "",
                        "content": f"【{actual_name}】暂离了小组",
                        "timestamp": int(time.time() * 1000)
                    }
                    await broadcast_to_room(old_room, leave_notice)

                    # 平滑移回 lobby
                    manager.move_user_to_room(websocket, "lobby", {
                        "name": actual_name,
                        "role": current_user_role,
                        "avatar": sender_record.get("avatar", ""),
                        "ip": client_ip
                    })

                    if len(old_room.members) > 0:
                        await broadcast_to_room(old_room, {
                            "type": "room_state",
                            "members": [m["name"] for m in old_room.members.values()],
                            "count": old_room.get_student_count(),
                            "max_players": old_room.max_players,
                            "creator": old_room.creator,
                            "choices_status": old_room.game.get_state().get("choices_status", {})
                        })

                    await broadcast_room_list_update()
                continue

            elif msg_type == "disband_room":
                curr = manager.get_room_for_ws(websocket)
                target_room_id = data.get("room_id", curr.room_id)
                is_teacher = (current_user_role == "teacher")
                success, deleted_room = manager.disband_room(target_room_id, actual_name, is_teacher)
                if success and deleted_room:
                    # 全员被移回 lobby 后，向原成员下发解散通知
                    disband_payload = {
                        "type": "room_disbanded",
                        "room_id": target_room_id,
                        "message": f"该小组已被{'教师' if is_teacher else '组长'}解散",
                        "timestamp": int(time.time() * 1000)
                    }
                    await broadcast_to_room(deleted_room, disband_payload)
                    await broadcast_room_list_update()
                continue

            # ---------------- 教师管控指令 ----------------
            if current_user_role == "teacher":
                if msg_type == "set_classroom_title":
                    new_title = data.get("title", "").strip()
                    if new_title:
                        manager.set_classroom_title(new_title)
                        await broadcast_to_all_rooms({
                            "type": "classroom_title_update",
                            "classroom_title": manager.classroom_title,
                            "timestamp": int(time.time() * 1000)
                        })
                        notice_msg = {
                            "type": "system",
                            "sender": "系统",
                            "role": "teacher",
                            "avatar": "",
                            "content": f"[班级更名] 教师已将班级名称修改为：【{manager.classroom_title}】",
                            "timestamp": int(time.time() * 1000)
                        }
                        manager.rooms["lobby"].add_message(notice_msg)
                        await broadcast_to_all_rooms(notice_msg)
                    continue

                elif msg_type == "set_global_mute":
                    manager.is_global_muted = data.get("is_muted", False)
                    await broadcast_to_all_rooms({
                        "type": "global_mute_state",
                        "is_muted": manager.is_global_muted,
                        "timestamp": int(time.time() * 1000)
                    })
                    continue

                elif msg_type == "start_buzz":
                    buzz_title = data.get("title", "快速抢答环节")
                    buzz_session.start(buzz_title)
                    await broadcast_to_all_rooms({
                        "type": "buzz_started",
                        "title": buzz_session.title,
                        "timestamp": buzz_session.start_timestamp_ms
                    })
                    card_msg = {
                        "type": "card_buzz",
                        "sender": "随堂互动",
                        "role": "teacher",
                        "avatar": "",
                        "content": buzz_session.title,
                        "timestamp": int(time.time() * 1000)
                    }
                    manager.rooms["lobby"].add_message(card_msg)
                    await broadcast_to_all_rooms(card_msg)
                    continue

                elif msg_type == "stop_buzz":
                    buzz_session.stop()
                    await broadcast_to_all_rooms({
                        "type": "buzz_stopped",
                        "timestamp": int(time.time() * 1000)
                    })
                    continue

                elif msg_type == "random_pick":
                    online_students = [
                        u["name"] for u in manager.roster.values()
                        if u.get("is_online") and u.get("role") != "teacher"
                    ]
                    if not online_students:
                        online_students = [
                            u["name"] for u in manager.roster.values()
                            if u.get("role") != "teacher"
                        ]
                    if not online_students:
                        online_students = list(manager.roster.keys())

                    winner_name, is_new_round, remaining_count = pick_session.pick_next(online_students)
                    pick_payload = {
                        "type": "random_pick_result",
                        "winner": winner_name,
                        "candidates": online_students,
                        "is_new_round": is_new_round,
                        "remaining_count": remaining_count,
                        "timestamp": int(time.time() * 1000)
                    }
                    await broadcast_to_all_rooms(pick_payload)

                    round_prefix = "[新一轮点名开启] " if is_new_round else ""
                    notice_msg = {
                        "type": "system",
                        "sender": "系统",
                        "role": "teacher",
                        "avatar": "",
                        "content": f"{round_prefix}[随机点名] 请【{winner_name}】同学回答问题 (本轮剩余未抽: {remaining_count}人)",
                        "timestamp": int(time.time() * 1000)
                    }
                    manager.rooms["lobby"].add_message(notice_msg)
                    await broadcast_to_all_rooms(notice_msg)
                    continue

                elif msg_type == "start_vote":
                    vote_title = data.get("title", "随堂单选测验")
                    vote_session.start(vote_title)
                    await broadcast_to_all_rooms(vote_session.get_state_payload())

                    card_msg = {
                        "type": "card_vote",
                        "sender": "随堂互动",
                        "role": "teacher",
                        "avatar": "",
                        "content": vote_session.title,
                        "timestamp": int(time.time() * 1000)
                    }
                    manager.rooms["lobby"].add_message(card_msg)
                    await broadcast_to_all_rooms(card_msg)
                    continue

                elif msg_type == "stop_vote":
                    if vote_session.is_active:
                        vote_session.stop()
                        final_state = vote_session.get_state_payload()
                        await broadcast_to_all_rooms(final_state)

                        counts = final_state["counts"]
                        total = final_state["total"]
                        summary_text = (
                            f"[随堂投票结果] 题目：{vote_session.title} | "
                            f"A:{counts['A']}票  B:{counts['B']}票  C:{counts['C']}票  D:{counts['D']}票 "
                            f"(共 {total} 人参与)"
                        )
                        notice_msg = {
                            "type": "system",
                            "sender": "系统",
                            "role": "teacher",
                            "avatar": "",
                            "content": summary_text,
                            "timestamp": int(time.time() * 1000)
                        }
                        manager.rooms["lobby"].add_message(notice_msg)
                        await broadcast_to_all_rooms(notice_msg)
                    continue

                elif msg_type == "admin_manage_student":
                    target_student = data.get("target_name")
                    operation = data.get("operation")
                    if target_student in manager.roster:
                        if operation == "toggle_mute":
                            new_mute_state = not manager.roster[target_student]["is_muted"]
                            manager.update_user_mute(target_student, new_mute_state)
                        elif operation == "set_role":
                            new_role = data.get("new_role", "student")
                            manager.update_user_role(target_student, new_role)
                        elif operation == "rename":
                            new_name = data.get("new_name", target_student).strip()
                            if new_name and new_name != target_student:
                                manager.rename_student(target_student, new_name)
                        await broadcast_roster_update()
                    continue

                elif msg_type == "publish_affair":
                    new_affair = manager.add_affair(
                        title=data.get("title", ""),
                        content=data.get("content", ""),
                        affair_type=data.get("affair_type", "notice"),
                        publisher=actual_name,
                        deadline=data.get("deadline", "")
                    )
                    await broadcast_to_all_rooms({
                        "type": "affair_update",
                        "affairs": [a.to_dict() for a in manager.affairs],
                        "new_item": new_affair.to_dict(),
                        "timestamp": int(time.time() * 1000)
                    })
                    continue

                elif msg_type == "edit_affair":
                    affair_id = data.get("affair_id")
                    if manager.edit_affair(
                        affair_id=affair_id,
                        title=data.get("title", ""),
                        content=data.get("content", ""),
                        affair_type=data.get("affair_type", "notice"),
                        deadline=data.get("deadline", "")
                    ):
                        await broadcast_to_all_rooms({
                            "type": "affair_update",
                            "affairs": [a.to_dict() for a in manager.affairs],
                            "timestamp": int(time.time() * 1000)
                        })
                    continue

                elif msg_type == "delete_affair":
                    affair_id = data.get("affair_id")
                    if manager.delete_affair(affair_id):
                        await broadcast_to_all_rooms({
                            "type": "affair_update",
                            "affairs": [a.to_dict() for a in manager.affairs],
                            "timestamp": int(time.time() * 1000)
                        })
                    continue

            # ---------------- 学生提交抢答 ----------------
            if msg_type == "submit_buzz":
                if current_user_role != "teacher" and buzz_session.submit(actual_name):
                    result_payload = {
                        "type": "buzz_result",
                        "winner": buzz_session.winner,
                        "cost_ms": buzz_session.cost_ms,
                        "title": buzz_session.title,
                        "timestamp": int(time.time() * 1000)
                    }
                    await broadcast_to_all_rooms(result_payload)

                    notice_msg = {
                        "type": "system",
                        "sender": "系统",
                        "role": "teacher",
                        "avatar": "",
                        "content": f"[抢答结果] 恭喜【{buzz_session.winner}】率先抢答成功，耗时 {buzz_session.cost_ms} ms",
                        "timestamp": int(time.time() * 1000)
                    }
                    manager.rooms["lobby"].add_message(notice_msg)
                    await broadcast_to_all_rooms(notice_msg)
                continue

            # ---------------- 学生提交随堂投票 ----------------
            if msg_type == "submit_vote":
                if current_user_role != "teacher":
                    option = data.get("option", "")
                    if vote_session.submit(actual_name, option):
                        await broadcast_to_all_rooms(vote_session.get_state_payload())
                continue

            # ---------------- 事务回执 ----------------
            if msg_type == "confirm_affair":
                if manager.confirm_affair(data.get("affair_id"), actual_name):
                    await broadcast_to_all_rooms({
                        "type": "affair_update",
                        "affairs": [a.to_dict() for a in manager.affairs],
                        "timestamp": int(time.time() * 1000)
                    })
                continue

            # ---------------- 组内互动决策（气泡卡片与对决路由） ----------------
            if msg_type == "initiate_game":
                curr_room = manager.get_room_for_ws(websocket)
                if curr_room and curr_room.room_id != "lobby":
                    target_wins = int(data.get("target_wins", 2))
                    rule_text = "单局决胜" if target_wins == 1 else "三局两胜"

                    curr_room.game.handle_action(actual_name, "start", {
                        "target_wins": target_wins
                    })

                    card_msg = {
                        "type": "card_rps",
                        "sender": actual_name,
                        "role": current_user_role,
                        "avatar": sender_record.get("avatar", ""),
                        "content": f"【{actual_name}】发起了猜拳 ({rule_text})",
                        "timestamp": int(time.time() * 1000)
                    }
                    curr_room.add_message(card_msg)
                    await broadcast_to_room(curr_room, card_msg)
                    await broadcast_to_room(curr_room, {
                        "type": "rps_state",
                        "state": curr_room.game.get_state()
                    })
                continue

            elif msg_type == "rps_join":
                curr_room = manager.get_room_for_ws(websocket)
                if curr_room and curr_room.room_id != "lobby":
                    curr_room.game.handle_action(actual_name, "join", {})
                    await broadcast_to_room(curr_room, {
                        "type": "rps_state",
                        "state": curr_room.game.get_state()
                    })
                continue

            elif msg_type == "rps_choice":
                curr_room = manager.get_room_for_ws(websocket)
                if curr_room and curr_room.room_id != "lobby":
                    action_res = curr_room.game.handle_action(actual_name, "choice", {"choice": data.get("choice")})
                    
                    # 局数决出胜负
                    if action_res.get("type") == "match_over":
                        await broadcast_to_room(curr_room, {
                            "type": "rps_state",
                            "state": action_res.get("state")
                        })
                        # 核心：自动向小组公屏落盘一条结论通报
                        summary_msg = {
                            "type": "system",
                            "sender": "系统",
                            "role": "teacher",
                            "avatar": "",
                            "content": action_res.get("summary", ""),
                            "timestamp": int(time.time() * 1000)
                        }
                        curr_room.add_message(summary_msg)
                        await broadcast_to_room(curr_room, summary_msg)

                    else:
                        await broadcast_to_room(curr_room, {
                            "type": "rps_state",
                            "state": action_res.get("state")
                        })
                continue

            elif msg_type == "rps_next_round":
                curr_room = manager.get_room_for_ws(websocket)
                if curr_room and curr_room.room_id != "lobby":
                    curr_room.game.handle_action(actual_name, "next_round", {})
                    await broadcast_to_room(curr_room, {
                        "type": "rps_state",
                        "state": curr_room.game.get_state()
                    })
                continue

            elif msg_type == "rps_reset":
                curr_room = manager.get_room_for_ws(websocket)
                if curr_room and curr_room.room_id != "lobby":
                    curr_room.game.handle_action(actual_name, "reset", {})
                    await broadcast_to_room(curr_room, {
                        "type": "rps_state",
                        "state": curr_room.game.get_state()
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
                        db.rename_user(actual_name, new_name)
                    else:
                        manager.roster[actual_name]["avatar"] = new_avatar

                    db.upsert_user(
                        name=actual_name,
                        role=manager.roster[actual_name]["role"],
                        avatar=manager.roster[actual_name]["avatar"],
                        is_muted=manager.roster[actual_name]["is_muted"],
                        last_seen=manager.roster[actual_name]["last_seen"]
                    )
                    current_room.members[websocket]["name"] = actual_name
                    await broadcast_roster_update()
                continue

            # ---------------- 讨论消息（组内私密广播） ----------------
            if msg_type in ("chat", "image"):
                if (manager.is_global_muted and current_user_role != "teacher") or sender_record.get("is_muted", False):
                    await websocket.send_text(json.dumps({
                        "type": "system",
                        "content": "[发言拦截] 您当前处于禁言状态或全员禁言已开启",
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
                current_room.add_message(chat_item)
                await broadcast_to_room(current_room, chat_item)

    except WebSocketDisconnect:
        handle_disconnect(websocket, actual_name)
        await broadcast_roster_update()
        await broadcast_room_list_update()
    except Exception:
        handle_disconnect(websocket, actual_name)
        await broadcast_roster_update()
        await broadcast_room_list_update()

def handle_disconnect(websocket: WebSocket, name: str):
    manager.remove_ws(websocket)
    is_still_online = any(
        ws_client != websocket and member_info.get("name") == name
        for r in manager.rooms.values()
        for ws_client, member_info in r.members.items()
    )
    if not is_still_online:
        manager.set_user_offline(name)

async def broadcast_roster_update():
    await broadcast_to_all_rooms({
        "type": "roster_update",
        "roster": manager.get_roster_list(),
        "timestamp": int(time.time() * 1000)
    })

async def broadcast_room_list_update():
    payload = {
        "type": "room_list",
        "rooms": manager.get_room_display_list(),
        "timestamp": int(time.time() * 1000)
    }
    await broadcast_to_all_rooms(payload)

async def broadcast_to_room(room: RoomInfo, payload: dict):
    raw = json.dumps(payload, ensure_ascii=False)
    for ws in list(room.members.keys()):
        try:
            await ws.send_text(raw)
        except Exception:
            pass

async def broadcast_to_all_rooms(payload: dict):
    raw = json.dumps(payload, ensure_ascii=False)
    for room in manager.rooms.values():
        for ws in list(room.members.keys()):
            try:
                await ws.send_text(raw)
            except Exception:
                pass