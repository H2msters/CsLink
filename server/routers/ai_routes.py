import json
import os
import time
import sqlite3
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
    clean_u = "".join(raw_u.split()).strip()
    return clean_u or default_u

def _get_provider_key(provider: str) -> str:
    default_k = DEFAULT_KEYS.get(provider, "")
    raw_k = db.get_setting(f"ai_key_{provider}", default_k)
    return "".join(raw_k.split()).strip() or default_k


# ==================== 班级图书角 RAG 深度检索引擎 ====================

def _load_all_books_from_system() -> List[Dict[str, str]]:
    """扫描 SQLite (classroom.db) 与本地图书目录中的所有馆藏书籍"""
    books: List[Dict[str, str]] = []
    seen_titles = set()

    db_path = getattr(db, "DB_PATH", "classroom.db")
    if not os.path.exists(db_path) and os.path.exists(os.path.join("server", "classroom.db")):
        db_path = os.path.join("server", "classroom.db")

    if os.path.exists(db_path):
        try:
            conn = sqlite3.connect(db_path)
            conn.row_factory = sqlite3.Row
            cur = conn.cursor()
            cur.execute("SELECT name FROM sqlite_master WHERE type='table'")
            tables = [r["name"] for r in cur.fetchall()]

            for tb in tables:
                if "book" in tb.lower():
                    cur.execute(f"SELECT * FROM {tb} LIMIT 100")
                    for row in cur.fetchall():
                        d = dict(row)
                        title = str(d.get("title") or d.get("name") or d.get("filename") or d.get("book_name") or "").strip()
                        if title and title not in seen_titles:
                            seen_titles.add(title)
                            books.append({
                                "title": title,
                                "author": str(d.get("author") or d.get("uploader") or "班级共享馆藏"),
                                "category": str(d.get("category") or d.get("tag") or "综合读物"),
                                "desc": str(d.get("description") or d.get("summary") or d.get("intro") or "馆藏 PDF 电子书，可在图书角直接在线阅读。")
                            })
            conn.close()
        except Exception:
            pass

    candidate_dirs = [
        "books", "uploads/books", "static/books", "data/books",
        os.path.join(os.path.dirname(os.path.dirname(__file__)), "books"),
        os.path.join(os.path.dirname(os.path.dirname(__file__)), "uploads", "books")
    ]
    for folder in candidate_dirs:
        if os.path.isdir(folder):
            try:
                for fname in os.listdir(folder):
                    if fname.lower().endswith((".pdf", ".txt", ".epub", ".md")):
                        clean_title = os.path.splitext(fname)[0]
                        if clean_title not in seen_titles:
                            seen_titles.add(clean_title)
                            fpath = os.path.join(folder, fname)
                            size_kb = max(1, os.path.getsize(fpath) // 1024)
                            books.append({
                                "title": clean_title,
                                "author": "班级图书角",
                                "category": "PDF电子书",
                                "desc": f"馆藏文件《{fname}》（约 {size_kb} KB），支持在软件「图书角」模块在线阅读。"
                            })
            except Exception:
                pass

    return books

def retrieve_book_corner_rag(user_prompt: str) -> str:
    """检索与用户问题相关的馆藏书籍并生成图书管理员知识库上下文"""
    books = _load_all_books_from_system()
    if not books:
        return "【班级图书角馆藏状态】：当前馆藏书籍为 0 本。若读者借阅或找书，请先推荐经典学科/课外名著，并温和提醒师生可在「图书角」上传 PDF 电子书丰富馆藏。"

    q_lower = user_prompt.lower()
    scored_books = []
    for b in books:
        score = 0
        text_pool = f"{b['title']} {b['author']} {b['category']} {b['desc']}".lower()
        if b["title"].lower() in q_lower or q_lower in b["title"].lower():
            score += 10
        for ch in set(q_lower):
            if len(ch.strip()) > 0 and ch not in "，。？！、的了是在有和与或吗呢吧啊书看读" and ch in text_pool:
                score += 1
        scored_books.append((score, b))

    scored_books.sort(key=lambda x: x[0], reverse=True)

    rag_lines = [f"【班级图书角实时馆藏目录（共收录 {len(books)} 本）】"]
    top_matches = [item[1] for item in scored_books[:5] if item[0] >= 2]

    if top_matches:
        rag_lines.append("▶ 馆藏重点匹配书目：")
        for idx, b in enumerate(top_matches, 1):
            rag_lines.append(f"  {idx}. 《{b['title']}》（作者/来源：{b['author']} | 分类：{b['category']}）—— {b['desc']}")

    all_titles = "、".join([f"《{b['title']}》" for b in books[:25]])
    rag_lines.append(f"▶ 馆藏完整书架清单：{all_titles}")
    return "\n".join(rag_lines)


# ==================== 图书管理员 & 知识百科全书 System Prompt ====================

def build_librarian_encyclopedia_prompt(username: str, role: str, model: str, user_score: int, cost: int, user_prompt: str) -> str:
    role_cn = "教师" if role.lower() == "teacher" else ("班长" if role.lower() == "monitor" else "同学")
    book_rag_context = retrieve_book_corner_rag(user_prompt)

    return (
        "你是本班的【图书管理员】。你的宗旨是：“你可以问我任何问题，我都会为你解答。”\n\n"
        "【一、你的核心定位】\n"
        "1. 馆藏导读：熟悉班级「图书角」的馆藏书目。当师生找书、求推荐、询问某本书或需要阅读指导时，优先结合下方的【班级图书角实时馆藏目录】进行精准推荐与导读；若馆藏暂无，则坦诚说明并推荐相关优秀书目。\n"
        "2. 解答万物：无论是学科难题、百科常识、代码调试还是日常写作，你都能给出清晰严谨、深入浅出的权威解答。\n\n"
        f"{book_rag_context}\n\n"
        "【二、背景感知】\n"
        f"- 当前对话读者：{username}（身份：{role_cn}，积分：{user_score}）。运行于《CsLink》内部。\n"
        "- 除非读者主动询问软件本身，否则无需主动罗列软件功能，专注于做好图书管理员的专业解答。\n\n"
        "【三、排版风格】\n"
        "使用清晰的段落、**重点加粗**和无序列表，语言温和凝练、直接切入重点。"
    )


# ==================== 路由接口定义 ====================

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

    system_prompt = build_librarian_encyclopedia_prompt(
        username=username,
        role=req.role,
        model=model,
        user_score=current_score,
        cost=cost,
        user_prompt=req.prompt
    )

    messages = [{"role": "system", "content": system_prompt}]
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
        "stream": True
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
                buffer_text = ""
                last_flush = time.monotonic()

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
                                buffer_text += delta
                                now = time.monotonic()
                                # 适度合并极碎的单字包（每 35ms 或满 4 个字推送一次），大幅减轻移动端渲染压力
                                if (now - last_flush) >= 0.035 or len(buffer_text) >= 4:
                                    yield f"data: {json.dumps({'content': buffer_text}, ensure_ascii=False)}\n\n"
                                    buffer_text = ""
                                    last_flush = now
                        except Exception:
                            pass

                if buffer_text:
                    yield f"data: {json.dumps({'content': buffer_text}, ensure_ascii=False)}\n\n"
        except Exception as e:
            yield f"data: {json.dumps({'error': str(e)}, ensure_ascii=False)}\n\n"

        nonlocal current_score
        if not is_teacher and cost > 0 and username:
            new_s = db.add_user_score(username, -cost, reason=f"AI提问 ({model})", operator="系统")
            if username in manager.roster:
                manager.roster[username]["score"] = new_s
            try:
                loop = asyncio.get_running_loop()
            except RuntimeError:
                loop = asyncio.new_event_loop()
                asyncio.set_event_loop(loop)
            loop.run_until_complete(broadcast_roster_update())
            yield f"data: {json.dumps({'score': new_s}, ensure_ascii=False)}\n\n"

        yield "data: [DONE]\n\n"

    return StreamingResponse(event_generator(), media_type="text/event-stream")