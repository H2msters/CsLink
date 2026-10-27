import json
import os
import re
import time
import sqlite3
import threading
import urllib.request
import urllib.error
import asyncio
from fastapi import APIRouter
from fastapi.responses import StreamingResponse
from pydantic import BaseModel
from typing import List, Dict, Optional, Tuple, Set
from core import db
from core.room_manager import manager
from routers.ws_routes import broadcast_roster_update

# 1. 导入原生 PDF 文本解析库
try:
    from pypdf import PdfReader
except ImportError:
    try:
        from PyPDF2 import PdfReader
    except ImportError:
        PdfReader = None

# 2. 导入扫描件渲染与离线 OCR 引擎（PyMuPDF + RapidOCR）
try:
    import fitz  # PyMuPDF：将扫描页渲染为图片
except ImportError:
    fitz = None

try:
    from rapidocr_onnxruntime import RapidOCR
    _OCR_ENGINE = RapidOCR()
except Exception:
    _OCR_ENGINE = None

# 3. 导入 Word 文档解析库 (.docx)
try:
    import docx
except ImportError:
    docx = None

router = APIRouter(prefix="/api/ai")

DEFAULT_URLS = {
    "qwen": "https://maas.qianwenalapi.com/compatible-mode/v1",
    "deepseek": "https://api.deepseek.com/v1",
    "glm": "https://open.bigmodel.cn/api/paas/v4"
}

DEFAULT_KEYS = {
    "qwen": os.getenv("QWEN_API_KEY", ""),
    "deepseek": os.getenv("DEEPSEEK_API_KEY", ""),
    "glm": os.getenv("GLM_API_KEY", "")
}

DEFAULT_COST = 1

# 内存缓存与后台 OCR 任务锁
_PDF_TEXT_CACHE: Dict[str, List[Tuple[int, str]]] = {}
_OCR_RUNNING_FILES: Set[str] = set()
_OCR_LOCK = threading.Lock()


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


# ==================== 扫描件 OCR 兜底与持久化缓存引擎 ====================

def _get_ocr_cache_path(file_path: str) -> str:
    return f"{file_path}.ocr.json"


def _load_ocr_disk_cache(file_path: str) -> Dict[int, str]:
    cache_file = _get_ocr_cache_path(file_path)
    if not os.path.isfile(cache_file):
        return {}
    try:
        mtime = os.path.getmtime(file_path)
        with open(cache_file, "r", encoding="utf-8") as f:
            data = json.load(f)
        if data.get("mtime") == mtime and isinstance(data.get("pages"), dict):
            return {int(k): str(v) for k, v in data["pages"].items()}
    except Exception:
        pass
    return {}


def _save_ocr_disk_cache(file_path: str, pages_dict: Dict[int, str]):
    cache_file = _get_ocr_cache_path(file_path)
    try:
        mtime = os.path.getmtime(file_path)
        payload = {
            "mtime": mtime,
            "pages": {str(k): v for k, v in sorted(pages_dict.items())}
        }
        with open(cache_file, "w", encoding="utf-8") as f:
            json.dump(payload, f, ensure_ascii=False, indent=2)
    except Exception:
        pass


def _ocr_single_pdf_page(doc, page_index: int) -> str:
    if fitz is None or _OCR_ENGINE is None:
        return ""
    try:
        page = doc.load_page(page_index)
        pix = page.get_pixmap(matrix=fitz.Matrix(1.5, 1.5), alpha=False)
        img_bytes = pix.tobytes("png")
        result, _ = _OCR_ENGINE(img_bytes)
        if not result:
            return ""
        lines = [str(item[1]).strip() for item in result if len(item) >= 2 and str(item[1]).strip()]
        return "\n".join(lines)
    except Exception:
        return ""


def _trigger_background_full_ocr(file_path: str, max_pages: int = 50):
    if fitz is None or _OCR_ENGINE is None:
        return
    with _OCR_LOCK:
        if file_path in _OCR_RUNNING_FILES:
            return
        _OCR_RUNNING_FILES.add(file_path)

    def _worker():
        try:
            pages_dict = _load_ocr_disk_cache(file_path)
            doc = fitz.open(file_path)
            total = min(len(doc), max_pages)
            updated = False
            for idx in range(total):
                page_num = idx + 1
                if page_num not in pages_dict or not pages_dict[page_num].strip():
                    ocr_text = _ocr_single_pdf_page(doc, idx)
                    if ocr_text:
                        pages_dict[page_num] = ocr_text
                        updated = True
                        if idx % 3 == 0:
                            _save_ocr_disk_cache(file_path, pages_dict)
            doc.close()
            if updated:
                _save_ocr_disk_cache(file_path, pages_dict)
                mtime = os.path.getmtime(file_path)
                cache_key = f"{file_path}_{mtime}"
                _PDF_TEXT_CACHE.pop(cache_key, None)
        except Exception:
            pass
        finally:
            with _OCR_LOCK:
                _OCR_RUNNING_FILES.discard(file_path)

    threading.Thread(target=_worker, daemon=True).start()


# ==================== 班级图书角多格式文档解析与 RAG 检索引擎 ====================

def _get_candidate_book_dirs() -> List[str]:
    server_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    project_root = os.path.dirname(server_root)
    dirs = [
        "books", "uploads/books", "static/books", "data/books", "uploads",
        os.path.join(server_root, "books"),
        os.path.join(server_root, "uploads", "books"),
        os.path.join(server_root, "static", "books"),
        os.path.join(server_root, "uploads"),
        os.path.join(project_root, "books"),
        os.path.join(project_root, "uploads", "books"),
    ]
    valid_dirs = []
    for d in dirs:
        if os.path.isdir(d):
            abs_d = os.path.abspath(d)
            if abs_d not in valid_dirs:
                valid_dirs.append(abs_d)
    return valid_dirs


def _find_local_file(filename_or_title: str) -> Optional[str]:
    if not filename_or_title:
        return None
    if os.path.isfile(filename_or_title):
        return os.path.abspath(filename_or_title)

    base_target = os.path.splitext(os.path.basename(filename_or_title))[0].lower()
    for folder in _get_candidate_book_dirs():
        try:
            for fname in os.listdir(folder):
                if not fname.lower().endswith((".pdf", ".txt", ".md", ".docx", ".doc")):
                    continue
                fpath = os.path.join(folder, fname)
                if fname.lower() == filename_or_title.lower() or os.path.splitext(fname)[0].lower() == base_target:
                    return os.path.abspath(fpath)
        except Exception:
            pass
    return None


def _parse_book_pages(file_path: str, priority_pages: Optional[Set[int]] = None, max_scan_pages: int = 80) -> List[Tuple[int, str]]:
    """
    解析图书角文件（支持 PDF、纯文本、Markdown 以及 Word .docx 文档）
    """
    if not file_path or not os.path.isfile(file_path):
        return []

    try:
        mtime = os.path.getmtime(file_path)
        cache_key = f"{file_path}_{mtime}"
        if cache_key in _PDF_TEXT_CACHE and not priority_pages:
            return _PDF_TEXT_CACHE[cache_key]

        pages_data: List[Tuple[int, str]] = []
        ext = os.path.splitext(file_path)[1].lower()

        # 1. 纯文本文件 (.txt / .md)
        if ext in (".txt", ".md"):
            with open(file_path, "r", encoding="utf-8", errors="ignore") as f:
                raw_text = f.read()
            chunk_size = 800
            for i in range(0, min(len(raw_text), chunk_size * max_scan_pages), chunk_size):
                seg = raw_text[i:i + chunk_size].strip()
                if seg:
                    pages_data.append((i // chunk_size + 1, seg))
            _PDF_TEXT_CACHE[cache_key] = pages_data
            return pages_data

        # 2. Word 文档 (.docx)
        if ext == ".docx" and docx is not None:
            doc = docx.Document(file_path)
            full_text_blocks = [p.text.strip() for p in doc.paragraphs if p.text.strip()]
            joined_text = "\n".join(full_text_blocks)
            chunk_size = 800
            for i in range(0, len(joined_text), chunk_size):
                seg = joined_text[i:i + chunk_size]
                if seg.strip():
                    pages_data.append((i // chunk_size + 1, seg))
            _PDF_TEXT_CACHE[cache_key] = pages_data
            return pages_data

        # 3. PDF 文件 (.pdf)：原生解析 + OCR 兜底
        if ext == ".pdf":
            ocr_cache = _load_ocr_disk_cache(file_path)
            ocr_cache_updated = False
            needs_background_ocr = False

            immediate_ocr_pages = {1, 2, 3}
            if priority_pages:
                immediate_ocr_pages.update(priority_pages)

            fitz_doc = fitz.open(file_path) if fitz is not None else None
            reader = PdfReader(file_path) if PdfReader is not None else None

            total_pages = 0
            if reader is not None:
                total_pages = min(len(reader.pages), max_scan_pages)
            elif fitz_doc is not None:
                total_pages = min(len(fitz_doc), max_scan_pages)

            for idx in range(total_pages):
                page_num = idx + 1
                page_text = ""

                if reader is not None:
                    try:
                        raw_page = reader.pages[idx].extract_text() or ""
                        clean_lines = [re.sub(r"\s+", " ", line).strip() for line in raw_page.splitlines() if line.strip()]
                        page_text = "\n".join(clean_lines)
                    except Exception:
                        pass
                if not page_text and fitz_doc is not None:
                    try:
                        raw_page = fitz_doc.load_page(idx).get_text("text") or ""
                        clean_lines = [re.sub(r"\s+", " ", line).strip() for line in raw_page.splitlines() if line.strip()]
                        page_text = "\n".join(clean_lines)
                    except Exception:
                        pass

                if len(page_text.strip()) < 15:
                    if page_num in ocr_cache and ocr_cache[page_num].strip():
                        page_text = f"[OCR识别] {ocr_cache[page_num]}"
                    elif fitz_doc is not None and _OCR_ENGINE is not None:
                        if page_num in immediate_ocr_pages:
                            ocr_res = _ocr_single_pdf_page(fitz_doc, idx)
                            if ocr_res:
                                ocr_cache[page_num] = ocr_res
                                ocr_cache_updated = True
                                page_text = f"[OCR识别] {ocr_res}"
                        else:
                            needs_background_ocr = True

                if page_text.strip():
                    pages_data.append((page_num, page_text))

            if fitz_doc is not None:
                fitz_doc.close()

            if ocr_cache_updated:
                _save_ocr_disk_cache(file_path, ocr_cache)

            if needs_background_ocr:
                _trigger_background_full_ocr(file_path, max_pages=min(total_pages, 50))

            if pages_data and not needs_background_ocr:
                _PDF_TEXT_CACHE[cache_key] = pages_data

        return pages_data
    except Exception:
        return []


def _extract_relevant_book_excerpts(file_path: str, user_prompt: str, max_chars: int = 1800) -> str:
    if not file_path or not os.path.isfile(file_path):
        return ""

    target_page_nums: Set[int] = set()
    for m in re.finditer(r"第\s*(\d+)\s*页", user_prompt):
        try:
            target_page_nums.add(int(m.group(1)))
        except Exception:
            pass

    pages = _parse_book_pages(file_path, priority_pages=target_page_nums)
    if not pages:
        return "（该文件暂未提取到可识别文字）"

    stop_chars = set("，。？！、；：“”‘’（）《》【】的了是在有和与或吗呢吧啊什怎么请问帮我看看读一本书第页章节内容讲")
    query_tokens = [ch for ch in set(user_prompt.lower()) if ch.strip() and ch not in stop_chars]

    scored_pages: List[Tuple[int, int, str]] = []
    for page_num, p_text in pages:
        p_lower = p_text.lower()
        score = 0
        if page_num in target_page_nums:
            score += 100
        for tk in query_tokens:
            if tk in p_lower:
                score += 2
        scored_pages.append((score, page_num, p_text))

    scored_pages.sort(key=lambda x: (-x[0], x[1]))

    selected_pages: List[Tuple[int, str]] = []
    seen_page_nums = set()

    for page_num, p_text in pages[:2]:
        selected_pages.append((page_num, p_text[:500]))
        seen_page_nums.add(page_num)

    for score, page_num, p_text in scored_pages:
        if len(selected_pages) >= 5:
            break
        if page_num not in seen_page_nums and score > 0:
            selected_pages.append((page_num, p_text[:700]))
            seen_page_nums.add(page_num)

    selected_pages.sort(key=lambda x: x[0])

    excerpts = []
    current_len = 0
    for page_num, snippet in selected_pages:
        block = f"【第 {page_num} 页/段原文节选】：\n{snippet}"
        if current_len + len(block) > max_chars:
            remain = max(200, max_chars - current_len)
            excerpts.append(block[:remain] + "...")
            break
        excerpts.append(block)
        current_len += len(block)

    return f"（已解析有效文本 {len(pages)} 块/页）\n" + "\n\n".join(excerpts)


def _load_all_books_from_system() -> List[Dict[str, str]]:
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
                        title = str(d.get("title") or d.get("name") or d.get("book_name") or d.get("filename") or "").strip()
                        raw_file = str(d.get("file_path") or d.get("filepath") or d.get("filename") or d.get("url") or title).strip()
                        clean_title = os.path.splitext(title)[0] if title.lower().endswith((".pdf", ".docx", ".doc")) else title

                        if clean_title and clean_title not in seen_titles:
                            seen_titles.add(clean_title)
                            real_path = _find_local_file(raw_file) or _find_local_file(clean_title) or ""
                            books.append({
                                "title": clean_title,
                                "author": str(d.get("author") or d.get("uploader") or "班级共享馆藏"),
                                "category": str(d.get("category") or d.get("tag") or "文档/电子书"),
                                "desc": str(d.get("description") or d.get("summary") or d.get("intro") or "班级图书角馆藏资料"),
                                "file_path": real_path
                            })
            conn.close()
        except Exception:
            pass

    for folder in _get_candidate_book_dirs():
        try:
            for fname in os.listdir(folder):
                if fname.lower().endswith((".pdf", ".txt", ".md", ".docx", ".doc")):
                    clean_title = os.path.splitext(fname)[0]
                    if clean_title not in seen_titles:
                        seen_titles.add(clean_title)
                        fpath = os.path.abspath(os.path.join(folder, fname))
                        size_kb = max(1, os.path.getsize(fpath) // 1024)
                        books.append({
                            "title": clean_title,
                            "author": "班级图书角",
                            "category": "文档/电子书",
                            "desc": f"馆藏文件《{fname}》（约 {size_kb} KB）",
                            "file_path": fpath
                        })
        except Exception:
            pass

    return books


def retrieve_book_corner_rag(user_prompt: str) -> str:
    books = _load_all_books_from_system()
    if not books:
        return "【班级图书角馆藏状态】：当前馆藏书籍为 0 本。若读者找书，请先推荐相关经典名著，并温和提醒师生可在「图书角」上传 PDF 或 Word 文档。"

    q_lower = user_prompt.lower()
    scored_books = []
    for b in books:
        score = 0
        title_lower = b["title"].lower()
        text_pool = f"{title_lower} {b['author']} {b['category']} {b['desc']}".lower()

        if title_lower in q_lower or q_lower in title_lower:
            score += 25
        for ch in set(q_lower):
            if len(ch.strip()) > 0 and ch not in "，。？！、的了是在有和与或吗呢吧啊书看读请问" and ch in text_pool:
                score += 1
        scored_books.append((score, b))

    scored_books.sort(key=lambda x: x[0], reverse=True)

    rag_lines = [f"【班级图书角实时馆藏目录（共收录 {len(books)} 本）】"]
    all_titles = "、".join([f"《{b['title']}》" for b in books[:25]])
    rag_lines.append(f"▶ 馆藏完整书架清单：{all_titles}")

    top_books = [item[1] for item in scored_books[:2] if item[0] >= 2]
    if not top_books and books:
        top_books = [books[0]]

    for idx, b in enumerate(top_books, 1):
        rag_lines.append(f"\n▶ 馆藏重点书目 {idx}：《{b['title']}》（作者/来源：{b['author']} | 分类：{b['category']} | 简介：{b['desc']}）")
        if b.get("file_path"):
            excerpt = _extract_relevant_book_excerpts(b["file_path"], user_prompt, max_chars=1800)
            if excerpt:
                rag_lines.append(f"📖《{b['title']}》原文实读解析内容：\n{excerpt}")

    return "\n".join(rag_lines)


# ==================== 图书管理员 System Prompt ====================

def build_librarian_encyclopedia_prompt(username: str, role: str, model: str, user_score: int, cost: int, user_prompt: str) -> str:
    role_cn = "教师" if role.lower() == "teacher" else ("班长" if role.lower() == "monitor" else "同学")
    book_rag_context = retrieve_book_corner_rag(user_prompt)

    return (
        "你是本班的【图书管理员】。你的宗旨是：“你可以问我任何问题，我都会为你解答。”\n\n"
        "【一、你的核心能力与职责】\n"
        "1. 精读馆藏图书与 Word/PDF 文档：你已直接接入班级「图书角」并具备实时阅读解析馆藏 PDF 与 Word 文档原文的能力。当师生询问某本馆藏资料讲了什么、某页/某段的内容、或者根据文档回答问题时，请直接依据下方的【原文实读解析内容】给出准确详实的回答。\n"
        "2. 馆藏荐书与导读：当师生找书或求推荐时，优先推荐【馆藏完整书架清单】中的资料；若馆藏中没有，请坦诚说明“目前班级图书角暂未收录该书”，再推荐国内外同类优秀著作。\n"
        "3. 全科百科解答：除图书文档外，师生向你请教任何学科难题、代码编程、常识百科或写作问题，你都会给出条理清晰、通俗易懂的解答。\n\n"
        f"{book_rag_context}\n\n"
        "【二、附带背景信息（仅在被主动问及时简短提及）】\n"
        f"- 当前对话读者：{username}（身份：{role_cn}，剩余积分：{user_score} 分，当前模型：{model}），运行在《CsLink》软件内。\n"
        "- 除非用户明确询问软件功能或积分，否则不要主动罗列软件的其他模块，专注于解答用户的问题。\n\n"
        "【三、排版要求】\n"
        "使用清晰的段落、**重点加粗**和无序列表进行排版，语气亲切、温和、专业。"
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