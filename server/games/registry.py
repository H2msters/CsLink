from typing import Dict, Type
from games.base import BaseGame
from games.rps import RPSGame
from games.buzz import BuzzGame

class GameRegistry:
    _registry: Dict[str, Type[BaseGame]] = {
        "rps": RPSGame,      # 猜拳游戏
        "buzz": BuzzGame,    # 课堂即时抢答器
    }

    @classmethod
    def create_game(cls, game_type: str) -> BaseGame:
        game_class = cls._registry.get(game_type)
        if not game_class:
            raise ValueError(f"不支持的游戏类型: {game_type}")
        return game_class()

    @classmethod
    def list_available_games(cls) -> list[str]:
        return list(cls._registry.keys())