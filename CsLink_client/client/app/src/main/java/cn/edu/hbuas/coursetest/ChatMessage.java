package cn.edu.hbuas.coursetest;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ChatMessage {
    private String type;     // chat / image / system / card_...
    private String sender;   // 发送者
    private String role;     // teacher / student / monitor / model:...
    private String content;  // 文本内容或图片Base64
    private String avatar;   // 头像Base64
    private boolean isMe;    // 是否是自己
    private long timestamp;  // 时间戳（毫秒）

    public ChatMessage(String type, String sender, String role, String content, String avatar, boolean isMe, long timestamp) {
        this.type = type;
        this.sender = sender;
        this.role = (role == null || role.trim().isEmpty()) ? "student" : role.trim();
        this.content = content;
        this.avatar = avatar;
        this.isMe = isMe;
        this.timestamp = timestamp > 0 ? timestamp : System.currentTimeMillis();
    }

    // 保留旧构造函数以向下兼容现有调用，默认赋予 student 角色
    public ChatMessage(String type, String sender, String content, String avatar, boolean isMe, long timestamp) {
        this(type, sender, "student", content, avatar, isMe, timestamp);
    }

    public String getType() {
        return type;
    }

    public String getSender() {
        return sender;
    }

    public String getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    // 核心新增：供 AI 流式打字机特效动态更新气泡文本
    public void setContent(String content) {
        this.content = content;
    }

    public String getAvatar() {
        return avatar;
    }

    public boolean isMe() {
        return isMe;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getFormattedTime() {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.getDefault());
        return sdf.format(new Date(timestamp));
    }
}