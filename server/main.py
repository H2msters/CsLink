from fastapi import FastAPI
import uvicorn
from routers.ws_routes import router as ws_router

app = FastAPI(title="Campus Interactive Hub")

# 挂载路由模块
app.include_router(ws_router)

if __name__ == "__main__":
    uvicorn.run("main:app", host="0.0.0.0", port=8000, reload=True)