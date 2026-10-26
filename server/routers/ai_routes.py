import json
import os
import urllib.request
import urllib.error
import asyncio
from fastapi import APIRouter
from fastapi.responses import StreamingResponse
from pydantic import BaseModel
from typing import List, Dict
from core import db
from core.room_manager import manager
from routers.ws_routes import broadcast_roster_update

router = APIRouter(prefix="/api/ai")

# 默认各厂商 OpenAI 兼容接口地址
DEFAULT_URLS = {
    "qwen": "https://maas.qianwenalapi.com/compatible-mode/v1",
    "deepseek": "https://api.deepseek.com/v1",
    "glm": "https://open.bigmodel.cn/api/paas/v4"
}

# 默认 API Key 兜底
DEFAULT_KEYS = {
    "qwen": os.getenv("QWEN_API_KEY", ""),
    "deepseek": os.getenv("DEEPSEEK_API_KEY", ""),
    "glm": os.getenv("GLM_API_KEY", "")
}

DEFAULT_COST = 1

class ChatRequest(BaseModel):
    username: str
    role: str = "student"
    provider: str = "qwen"
    model: str = "qwen3.8-flash"
    prompt: str
    history: List[Dict[str, str]] = []

class StreamChatRequest(BaseModel):
    username: str
    role: str = "student"
    provider: str = "qwen"
    model: str = "qwen3.8-flash"
    prompt: str
    history: List[Dict[str, str]] = []

class ConfigRequest(BaseModel):
    provider: str = "qwen"
    api_key: str = ""
    base_url: str = ""
    cost: int = 1

def _get_provider_url(provider: str) -> str:
    default_u = DEFAULT_URLS.get(provider, DEFAULT_URLS["qwen"])
    raw_u = db.get_setting(f"ai_url_{provider}", default_u)
    # 自动过滤掉输入或粘贴时带入的换行符、回车和空格
    clean_u = "".join(raw_u.split()).strip()
    return clean_u or default_u

def _get_provider_key(provider: str) -> str:
    default_k = DEFAULT_KEYS.get(provider, "")
    raw_k = db.get_setting(f"ai_key_{provider}", default_k)
    return "".join(raw_k.split()).strip() or default_k

@router.get("/status")
async def get_ai_status(username: str = ""):
    users = db.load_all_users()
    user_score = users.get(username, {}).get("score", 0)
    if username in manager.roster:
        user_score = manager.roster[username].get("score", user_score)

    cost_str = db.get_setting("ai_cost", str(DEFAULT_COST))
    cost = int(cost_str) if cost_str.isdigit() else DEFAULT_COST

    provider_urls = {p: _get_provider_url(p) for p in ("qwen", "deepseek", "glm")}

    return {
        "code": 200,
        "score": user_score,
        "cost": cost,
        "provider_urls": provider_urls
    }

@router.post("/config")
async def update_ai_config(req: ConfigRequest):
    p = req.provider.strip().lower() or "qwen"
    if req.api_key.strip():
        clean_key = "".join(req.api_key.split()).strip()
        db.set_setting(f"ai_key_{p}", clean_key)
    if req.base_url.strip():
        clean_url = "".join(req.base_url.split()).strip()
        db.set_setting(f"ai_url_{p}", clean_url)
    db.set_setting("ai_cost", str(max(0, req.cost)))
    return {"code": 200, "message": "配置已保存"}

@router.post("/stream_chat")
async def ai_stream_chat(req: StreamChatRequest):
    username = req.username.strip()
    is_teacher = (req.role.lower() == "teacher")
    provider = req.provider.strip().lower() or "qwen"
    model = req.model.strip() or "qwen3.8-flash"

    cost_str = db.get_setting("ai_cost", str(DEFAULT_COST))
    cost = 0 if is_teacher else (int(cost_str) if cost_str.isdigit() else DEFAULT_COST)

    users = db.load_all_users()
    current_score = users.get(username, {}).get("score", 0)
    if username in manager.roster:
        current_score = manager.roster[username].get("score", current_score)

    # 1. 校验积分是否充足
    if not is_teacher and current_score < cost:
        def err_gen():
            yield f"data: {json.dumps({'error': f'积分不足（当前 {current_score} 分，需 {cost} 分）'}, ensure_ascii=False)}\n\n"
        return StreamingResponse(err_gen(), media_type="text/event-stream")

    api_key = _get_provider_key(provider)
    base_url = _get_provider_url(provider)

    if not api_key:
        def err_gen():
            yield f"data: {json.dumps({'error': f'尚未配置【{provider.upper()}】的 API Key，请前往 API 管理填入'}, ensure_ascii=False)}\n\n"
        return StreamingResponse(err_gen(), media_type="text/event-stream")

    messages = [{"role": "system", "content": "你是数字化课堂的智能助教，回答请简洁、准确、条理清晰。"}]
    for h in req.history[-6:]:
        if h.get("role") in ("user", "assistant") and h.get("content"):
            messages.append({"role": h["role"], "content": h["content"]})
    messages.append({"role": "user", "content": req.prompt})

    url = base_url.rstrip("/")
    if not url.endswith("/chat/completions"):
        url += "/chat/completions"

    payload = {
        "model": model,
        "messages": messages,
        "temperature": 0.7,
        "stream": True  # 开启 SSE 流式响应
    }

    def event_generator():
        data_bytes = json.dumps(payload).encode("utf-8")
        headers = {
            "Content-Type": "application/json",
            "Authorization": f"Bearer {api_key}",
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) OpenAI-Compatible-Client/1.0"
        }
        try:
            http_req = urllib.request.Request(url, data=data_bytes, headers=headers, method="POST")
            with urllib.request.urlopen(http_req, timeout=60) as resp:
                for line in resp:
                    line_str = line.decode("utf-8").strip()
                    if line_str.startswith("data:"):
                        content_data = line_str[5:].strip()
                        if content_data == "[DONE]":
                            break
                        try:
                            chunk_json = json.loads(content_data)
                            delta = chunk_json["choices"][0]["delta"].get("content", "")
                            if delta:
                                yield f"data: {json.dumps({'content': delta}, ensure_ascii=False)}\n\n"
                        except Exception:
                            pass
        except Exception as e:
            yield f"data: {json.dumps({'error': str(e)}, ensure_ascii=False)}\n\n"

        # 2. 生成完毕后扣除学生积分并实时广播
        nonlocal current_score
        if not is_teacher and cost > 0 and username:
            new_s = db.add_user_score(username, -cost, reason=f"AI提问 ({model})", operator="系统")
            if username in manager.roster:
                manager.roster[username]["score"] = new_s
            import asyncio
            try:
                loop = asyncio.get_running_loop()
            except RuntimeError:
                loop = asyncio.new_event_loop()
                asyncio.set_event_loop(loop)
            loop.run_until_complete(broadcast_roster_update())
            yield f"data: {json.dumps({'score': new_s}, ensure_ascii=False)}\n\n"

        yield "data: [DONE]\n\n"

    return StreamingResponse(event_generator(), media_type="text/event-stream")