package cn.edu.hbuas.coursetest;

import java.util.ArrayList;
import java.util.List;

public class RosterDataBridge {

    public interface OnRosterChangeListener {
        void onRosterChanged(List<MainActivity.RosterUser> latestRoster);
        void onClassroomTitleChanged(String title);
    }

    private static OnRosterChangeListener activeListener;
    private static final List<MainActivity.RosterUser> cachedRoster = new ArrayList<>();
    private static String cachedClassroomTitle = "数字化课堂 [讨论频道]";

    public static synchronized void setListener(OnRosterChangeListener listener) {
        activeListener = listener;
    }

    public static synchronized void clearListener() {
        activeListener = null;
    }

    public static synchronized void updateRoster(List<MainActivity.RosterUser> roster) {
        cachedRoster.clear();
        if (roster != null) {
            cachedRoster.addAll(roster);
        }
        if (activeListener != null) {
            activeListener.onRosterChanged(new ArrayList<>(cachedRoster));
        }
    }

    public static synchronized void setClassroomTitle(String title) {
        if (title != null && !title.trim().isEmpty()) {
            cachedClassroomTitle = title.trim();
            if (activeListener != null) {
                activeListener.onClassroomTitleChanged(cachedClassroomTitle);
            }
        }
    }

    public static synchronized List<MainActivity.RosterUser> getCachedRoster() {
        return new ArrayList<>(cachedRoster);
    }

    public static synchronized String getCachedClassroomTitle() {
        return cachedClassroomTitle;
    }
}