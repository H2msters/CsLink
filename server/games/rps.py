from games.base import BaseGame
from typing import Dict, Any

class RPSGame(BaseGame):
    def __init__(self):
        self.choices: Dict[str, str] = {}
        self.result_text: str = ""

    def handle_action(self, player_id: str, action: str, data: Dict[str, Any]) -> Dict[str, Any]:
        if action == "choice":
            choice = data.get("choice", "")
            self.choices[player_id] = choice
            
            # 满两人结算
            if len(self.choices) >= 2:
                players = list(self.choices.keys())
                p1, p2 = players[0], players[1]
                c1, c2 = self.choices[p1], self.choices[p2]
                
                winner = "draw" if c1 == c2 else (
                    p1 if (c1, c2) in [("石头", "剪刀"), ("剪刀", "布"), ("布", "石头")] else p2
                )
                self.result_text = "平局！" if winner == "draw" else f"【{winner}】获胜！"
                return {"type": "game_over", "winner": winner, "detail": self.choices, "result": self.result_text}
            
            return {"type": "player_acted", "player": player_id}
        return {}

    def get_state(self) -> Dict[str, Any]:
        return {"choices_status": {p: True for p in self.choices}, "result": self.result_text}

    def reset(self):
        self.choices.clear()
        self.result_text = ""