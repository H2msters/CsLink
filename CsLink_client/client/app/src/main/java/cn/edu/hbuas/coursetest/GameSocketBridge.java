package cn.edu.hbuas.coursetest;

import org.json.JSONObject;
import okhttp3.WebSocket;

public class GameSocketBridge {

    public interface RoomMessageListener {
        void onMessageReceived(JSONObject json);
    }

    private static WebSocket sharedWebSocket;
    private static RoomMessageListener activeListener;

    public static void setWebSocket(WebSocket ws) {
        sharedWebSocket = ws;
    }

    public static void setListener(RoomMessageListener listener) {
        activeListener = listener;
    }

    public static void clearListener() {
        activeListener = null;
    }

    public static void dispatchMessage(JSONObject json) {
        if (activeListener != null) {
            activeListener.onMessageReceived(json);
        }
    }

    public static boolean sendJson(JSONObject json) {
        if (sharedWebSocket != null && json != null) {
            return sharedWebSocket.send(json.toString());
        }
        return false;
    }
}