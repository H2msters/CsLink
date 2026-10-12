from games.base import BaseGame
from typing import Dict, Any, Optional

class BuzzGame(BaseGame):
    def __init__(self):
        self.winner: Optional[str] = None

    def handle_action(self, player_id: str, action: str, data: Dict[str, Any]) -> Dict[str, Any]:
        if action == "buzz" and self.winner is None:
            self.winner = player_id
            return {"type": "game_over", "winner": self.winner, "result": f"【{player_id}】抢答成功！"}
        return {}

    def get_state(self) -> Dict[str, Any]:
        return {"has_winner": self.winner is not None, "winner": self.winner}

    def reset(self):
        self.winner = None