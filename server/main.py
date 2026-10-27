from fastapi import FastAPI
import uvicorn
from routers import ws_routes, book_routes, ai_routes

app = FastAPI(title="CsLink")

app.include_router(ws_routes.router)
app.include_router(book_routes.router)
app.include_router(ai_routes.router)

if __name__ == "__main__":
    uvicorn.run("main:app", host="0.0.0.0", port=8000, reload=True)