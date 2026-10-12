from abc import ABC, abstractmethod
from typing import Dict, Any

class BaseGame(ABC):
    """所有独立游戏插件必须遵循的统一抽象基类"""
    
    @abstractmethod
    def handle_action(self, player_id: str, action: str, data: Dict[str, Any]) -> Dict[str, Any]:
        """处理玩家的操作（例如出拳、按下抢答按钮、落子等）"""
        pass

    @abstractmethod
    def get_state(self) -> Dict[str, Any]:
        """获取当前游戏画面的同步状态（发送给客户端渲染）"""
        pass

    @abstractmethod
    def reset(self) -> None:
        """重置游戏（再来一局）"""
        pass