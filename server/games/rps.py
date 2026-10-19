from games.base import BaseGame
from typing import Dict, Any, Optional

class RPSGame(BaseGame):
    def __init__(self):
        self.target_wins: int = 2  # 默认三局两胜(先得2分)
        self.p1: Optional[str] = None
        self.p2: Optional[str] = None
        self.scores: Dict[str, int] = {}
        self.current_round: int = 1
        self.choices: Dict[str, str] = {}
        self.round_result_text: str = ""
        self.final_winner: Optional[str] = None
        self.final_loser: Optional[str] = None
        self.status: str = "idle"

    def handle_action(self, player_id: str, action: str, data: Dict[str, Any]) -> Dict[str, Any]:
        # 1. 发起
        if action in ("start", "create"):
            self.reset()
            self.target_wins = int(data.get("target_wins", 2))
            self.p1 = player_id
            self.scores[self.p1] = 0
            self.status = "waiting_opponent"
            self.round_result_text = "等待对手加入..."
            return {"type": "game_state", "state": self.get_state()}

        # 2. 应战加入
        elif action == "join":
            if not self.p1:
                self.p1 = player_id
                self.scores[self.p1] = 0
                self.status = "waiting_opponent"
            elif self.p1 == player_id:
                pass
            elif not self.p2:
                self.p2 = player_id
                self.scores[self.p2] = 0
                self.status = "playing"
                self.round_result_text = "双方就位，请出拳"
            return {"type": "game_state", "state": self.get_state()}

        # 3. 玩家出拳
        elif action == "choice":
            if self.status not in ("playing", "waiting_opponent"):
                return {"type": "error", "message": "当前不可出拳"}

            if not self.p2 and player_id != self.p1:
                self.p2 = player_id
                self.scores[self.p2] = 0
                self.status = "playing"

            if player_id in (self.p1, self.p2):
                choice = data.get("choice", "").strip()
                if choice in ("石头", "剪刀", "布"):
                    self.choices[player_id] = choice

                # 双方均已出拳
                if self.p1 in self.choices and self.p2 in self.choices:
                    c1 = self.choices[self.p1]
                    c2 = self.choices[self.p2]

                    if c1 == c2:
                        round_winner = "draw"
                        self.round_result_text = f"平局，双方均出【{c1}】"
                    elif (c1, c2) in [("石头", "剪刀"), ("剪刀", "布"), ("布", "石头")]:
                        round_winner = self.p1
                        self.scores[self.p1] += 1
                        self.round_result_text = f"第 {self.current_round} 局：【{self.p1}】胜出 ({c1} 胜 {c2})"
                    else:
                        round_winner = self.p2
                        self.scores[self.p2] += 1
                        self.round_result_text = f"第 {self.current_round} 局：【{self.p2}】胜出 ({c2} 胜 {c1})"

                    # 胜场判定：标注谁战胜了谁
                    if self.scores.get(self.p1, 0) >= self.target_wins:
                        self.final_winner = self.p1
                        self.final_loser = self.p2
                        self.status = "match_finished"
                        return {
                            "type": "match_over",
                            "final_winner": self.final_winner,
                            "final_loser": self.final_loser,
                            "summary": f"[猜拳结果] 【{self.p1}】战胜了【{self.p2}】({self.scores[self.p1]}:{self.scores[self.p2]})",
                            "state": self.get_state()
                        }
                    elif self.scores.get(self.p2, 0) >= self.target_wins:
                        self.final_winner = self.p2
                        self.final_loser = self.p1
                        self.status = "match_finished"
                        return {
                            "type": "match_over",
                            "final_winner": self.final_winner,
                            "final_loser": self.final_loser,
                            "summary": f"[猜拳结果] 【{self.p2}】战胜了【{self.p1}】({self.scores[self.p2]}:{self.scores[self.p1]})",
                            "state": self.get_state()
                        }
                    else:
                        self.status = "round_finished"
                        return {
                            "type": "round_over",
                            "round_winner": round_winner,
                            "result": self.round_result_text,
                            "state": self.get_state()
                        }
                else:
                    return {"type": "player_acted", "player": player_id, "state": self.get_state()}
            return {}

        # 4. 下一小局
        elif action == "next_round":
            if self.status == "round_finished":
                self.choices.clear()
                self.current_round += 1
                self.status = "playing"
                self.round_result_text = f"第 {self.current_round} 局，请出拳"
                return {"type": "game_state", "state": self.get_state()}
            return {}

        return {}

    def get_state(self) -> Dict[str, Any]:
        return {
            "status": self.status,
            "target_wins": self.target_wins,
            "current_round": self.current_round,
            "p1": self.p1,
            "p2": self.p2,
            "scores": self.scores,
            "choices_status": {p: True for p in self.choices},
            "choices": self.choices if self.status in ("round_finished", "match_finished") else {},
            "round_result": self.round_result_text,
            "final_winner": self.final_winner,
            "final_loser": self.final_loser
        }

    def reset(self):
        self.target_wins = 2
        self.p1 = None
        self.p2 = None
        self.scores.clear()
        self.current_round = 1
        self.choices.clear()
        self.round_result_text = ""
        self.final_winner = None
        self.final_loser = None
        self.status = "idle"