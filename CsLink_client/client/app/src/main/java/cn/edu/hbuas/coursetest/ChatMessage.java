package cn.edu.hbuas.coursetest;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ChatMessage {
    private String type;     // chat / image / system
    private String sender;   // 发送者
    private String content;  // 文本内容或图片Base64
    private String avatar;   // 头像Base64
    private boolean isMe;    // 是否是自己
    private long timestamp;  // 时间戳（毫秒）

    public ChatMessage(String type, String sender, String content, String avatar, boolean isMe, long timestamp) {
        this.type = type;
        this.sender = sender;
        this.content = content;
        this.avatar = avatar;
        this.isMe = isMe;
        this.timestamp = timestamp > 0 ? timestamp : System.currentTimeMillis();
    }

    public String getType() {
        return type;
    }

    public String getSender() {
        return sender;
    }

    public String getContent() {
        return content;
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