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
    """教师上传 PDF 书籍"""
    if not file.filename.lower().endswith(".pdf"):
        raise HTTPException(status_code=400, detail="只允许上传 PDF 格式文件")

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
    """读取并下载 PDF 文件"""
    file_path = os.path.join(STORAGE_BOOKS_DIR, filename)
    if not os.path.exists(file_path):
        raise HTTPException(status_code=404, detail="文件不存在")
    return FileResponse(file_path, media_type="application/pdf", filename=filename)

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