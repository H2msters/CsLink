package cn.edu.hbuas.coursetest;

import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import okhttp3.WebSocket;

public class GameSocketBridge {

    public interface RoomMessageListener {
        void onMessageReceived(JSONObject json);
    }

    private static WebSocket sharedWebSocket;
    private static RoomMessageListener activeListener;
    // 竞态缓冲区：缓冲进组瞬间因 Activity 未初始化完毕而掉落的消息
    private static final List<JSONObject> pendingMessages = new ArrayList<>();

    public static void setWebSocket(WebSocket ws) {
        sharedWebSocket = ws;
    }

    public static synchronized void setListener(RoomMessageListener listener) {
        activeListener = listener;
        if (activeListener != null && !pendingMessages.isEmpty()) {
            for (JSONObject msg : pendingMessages) {
                activeListener.onMessageReceived(msg);
            }
            pendingMessages.clear();
        }
    }

    public static synchronized void clearListener() {
        activeListener = null;
        pendingMessages.clear();
    }

    public static synchronized void dispatchMessage(JSONObject json) {
        if (activeListener != null) {
            activeListener.onMessageReceived(json);
        } else if (json != null) {
            pendingMessages.add(json);
        }
    }

    public static boolean sendJson(JSONObject json) {
        if (sharedWebSocket != null && json != null) {
            return sharedWebSocket.send(json.toString());
        }
        return false;
    }
}