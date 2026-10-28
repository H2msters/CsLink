[cslink.md](https://github.com/user-attachments/files/34073626/cslink.md)
# CsLink 课堂协同与知识检索系统

CsLink 是一套基于 Android 原生客户端与 Python FastAPI 服务端构建的课堂教学协同系统。系统集成了多模型流式问答、班级图书文档检索增强生成（RAG）以及基于 WebSocket 的实时课堂互动功能。

## 一、 系统功能模块

### 1. 多模型 AI 问答与知识检索平台
* **多模型接入**：支持通义千问（`qwen3.8-flash`）、DeepSeek（`deepseek-v4.1-flash-1017`）与智谱清言（`glm-4-flash`）三种模型的切换与调用。
* **联网检索支持**：后端针对 DeepSeek 模型集成了官方网页搜索接口（Search API），支持对实时资讯类问题进行联网检索并注入上下文。
* **流式传输与局部渲染**：服务端基于 SSE（Server-Sent Events）协议推送流式响应；客户端通过帧率节流与 `RecyclerView` 局部 `Payload` 刷新机制渲染 Markdown 文本，避免界面闪烁与重复布局计算。
* **会话持久化与权限控制**：客户端基于 `SharedPreferences` 按用户及模型维度存储本地历史对话记录。支持教师端在线配置 API Key、Base URL 及单次提问积分消耗；学生端提问自动扣减积分并实时同步至花名册。

### 2. 班级图书角与多格式文档解析（RAG）
* **多格式文档管理**：支持教师端上传与管理 PDF（`.pdf`）及 Word（`.docx` / `.doc`）格式的教学文档与电子书，客户端提供 PDF 渲染视图与 Word 文本解析视图。
* **文档分片与上下文检索**：服务端在进行 AI 对话时自动检索图书角馆藏目录，根据提问关键词及指定页码匹配相关文档段落，将原文节选注入系统提示词（System Prompt）以实现基于本地文档的问答。
* **扫描件离线 OCR 识别**：针对无原生文字层的扫描版 PDF 文件，服务端集成 `PyMuPDF` 与 `RapidOCR` 离线推理引擎提取图像文字，并通过后台异步线程与 `.ocr.json` 本地文件缓存识别结果，降低重复解析开销。

### 3. 实时课堂互动与协同管理
* **实时通讯**：基于 WebSocket 协议实现课堂公共聊天室、班级事务通知发布与在线成员状态同步。
* **多人协同画板**：提供 `CollaborativeCanvasView` 组件，支持多终端实时同步绘制轨迹。
* **课堂互动房间**：内置互动问答与课堂活动模块（`GameRoomActivity`）。
* **花名册与积分管理**：支持查看班级成员在线状态、角色权限（教师、班长、学生）及积分变动明细，支持本地相机拍摄与头像裁剪上传。

## 二、 目录结构

```text
CsLink/
├── CsLink_client/
│   └── client/                        # Android 客户端工程
│       └── app/src/main/
│           ├── java/cn/edu/hbuas/coursetest/
│           │   ├── MainActivity.java              # 课堂主界面与群聊模块
│           │   ├── AiPlatformActivity.java        # 多模型 AI 问答界面
│           │   ├── BookCornerActivity.java        # 图书角文档上传与列表管理
│           │   ├── PdfViewerActivity.java         # PDF 文档阅读器
│           │   ├── CollaborativeCanvasView.java   # 多人实时协同画板视图
│           │   ├── GameRoomActivity.java          # 课堂互动房间模块
│           │   ├── RosterActivity.java            # 花名册与积分管理界面
│           │   ├── SettingsActivity.java          # 个人设置与服务端地址配置
│           │   └── ChatAdapter.java               # 消息列表与流式文本渲染适配器
│           └── res/                               # 布局文件与图标资源
│
└── server/                            # FastAPI 服务端工程
    ├── main.py                        # 服务端入口与路由注册
    ├── requirements.txt               # Python 依赖包列表
    ├── classroom.db                   # SQLite 数据库文件
    ├── storage/
    │   └── books/                     # 上传文档存储与 OCR 缓存目录
    ├── core/
    │   ├── db.py                      # SQLite 数据库操作封装
    │   └── room_manager.py            # WebSocket 连接池与状态管理
    └── routers/
        ├── ai_routes.py               # AI 流式对话、RAG 检索与 OCR 解析路由
        ├── book_routes.py             # 图书文档上传、下载与删除路由
        └── ws_routes.py               # WebSocket 实时通信路由
```

## 三、 部署与运行说明

### 1. 服务端部署

1. 克隆仓库并进入服务端目录：
   ```bash
   git clone https://github.com/H2msters/CsLink.git
   cd CsLink/server
   ```

2. 安装 Python 依赖（建议使用 Python 3.9 及以上版本）：
   ```bash
   pip install -r requirements.txt
   ```
   主要依赖项：`fastapi`、`uvicorn`、`pypdf`、`pymupdf`、`rapidocr-onnxruntime`、`python-docx`、`python-multipart`。

3. 启动服务：
   ```bash
   python main.py
   # 或使用 uvicorn 命令行启动：
   uvicorn main:app --host 0.0.0.0 --port 8000
   ```
   服务默认监听 `0.0.0.0:8000`。首次启动时将自动初始化 `classroom.db` 数据库及 `storage/books` 存储目录。

### 2. 客户端编译与配置

1. 使用 Android Studio 打开 `CsLink_client/client` 目录，完成 Gradle 依赖同步后编译安装至 Android 设备。
2. 网络连接配置：
   * **局域网环境**：确保移动设备与服务端主机处于同一局域网，在客户端设置界面填入服务端局域网 IP 地址（默认端口为 `8000`）。
   * **跨网段连接**：可通过虚拟局域网工具（如 Tailscale 等）组建异地局域网，在客户端填入服务端分配的虚拟 IP 地址进行连接。

## 四、 接入模型说明

| 模型提供方 | 默认模型标识 | 适用场景与特性 |
| :--- | :--- | :--- |
| 通义千问 (Qwen) | `qwen3.8-flash` | 文本生成与本地文档上下文阅读解析 |
| DeepSeek | `deepseek-v4.1-flash-1017` | 逻辑推理、代码解析及实时网页搜索 |
| 智谱清言 (GLM) | `glm-4-flash` | 通用中文对话与基础知识问答 |

注：以教师角色登录后，可在 AI 问答界面右上角「API 管理」中修改各模型的 API Key、接口地址（Base URL）及单次提问积分参数。

## 五、 许可证

本项目采用 [MIT License](LICENSE) 许可证。
