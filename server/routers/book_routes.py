import os
import time
import shutil
from fastapi import APIRouter, UploadFile, File, Form, HTTPException
from fastapi.responses import FileResponse
from core import db

# 必须显式暴露 router 供 main.py 挂载
router = APIRouter(prefix="/api/books")

STORAGE_BOOKS_DIR = os.path.join(os.path.dirname(os.path.dirname(__file__)), "storage", "books")
os.makedirs(STORAGE_BOOKS_DIR, exist_ok=True)

@router.get("/list")
async def list_books():
    """获取所有已上传书籍名录"""
    return {"code": 200, "books": db.load_all_books()}

@router.post("/upload")
async def upload_book(
    file: UploadFile = File(...),
    title: str = Form(...),
    uploader: str = Form("教师")
):
    """教师上传 PDF 或 Word 文档书籍"""
    filename_lower = file.filename.lower()
    if not filename_lower.endswith((".pdf", ".docx", ".doc")):
        raise HTTPException(status_code=400, detail="只允许上传 PDF 或 Word (.docx/.doc) 格式文件")

    now_ts = int(time.time() * 1000)
    book_id = f"book_{now_ts}"
    safe_filename = f"{book_id}_{file.filename}"
    file_path = os.path.join(STORAGE_BOOKS_DIR, safe_filename)

    with open(file_path, "wb") as buffer:
        shutil.copyfileobj(file.file, buffer)

    file_size = os.path.getsize(file_path)
    clean_title = title.strip() if title.strip() else file.filename.rsplit(".", 1)[0]
    db.insert_book(book_id, clean_title, safe_filename, file_size, uploader, now_ts)

    return {"code": 200, "message": "上传成功", "book_id": book_id}

@router.get("/file/{filename}")
async def get_book_file(filename: str):
    """读取并下载文件（支持 PDF 和 Word 格式）"""
    file_path = os.path.join(STORAGE_BOOKS_DIR, filename)
    if not os.path.exists(file_path):
        raise HTTPException(status_code=404, detail="文件不存在")
    
    ext = filename.lower().split(".")[-1]
    if ext == "pdf":
        media_type = "application/pdf"
    elif ext in ("docx", "doc"):
        media_type = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    else:
        media_type = "application/octet-stream"

    return FileResponse(file_path, media_type=media_type, filename=filename)

@router.delete("/{book_id}")
async def delete_book(book_id: str):
    """删除书籍记录并清理磁盘文件"""
    filename = db.delete_book_db(book_id)
    if filename:
        file_path = os.path.join(STORAGE_BOOKS_DIR, filename)
        if os.path.exists(file_path):
            os.remove(file_path)
        return {"code": 200, "message": "删除成功"}
    raise HTTPException(status_code=404, detail="书籍未找到")