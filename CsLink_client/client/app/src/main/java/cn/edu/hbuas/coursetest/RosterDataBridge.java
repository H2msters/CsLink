package cn.edu.hbuas.coursetest;

import org.json.JSONArray;
import java.util.ArrayList;
import java.util.List;

public class RosterDataBridge {

    public interface OnRosterChangeListener {
        void onRosterChanged(List<MainActivity.RosterUser> latestRoster);
        void onClassroomTitleChanged(String title);
        default void onAttendanceLogsReceived(String date, JSONArray logs, JSONArray activeDates) {}
    }

    private static OnRosterChangeListener activeListener;
    private static final List<MainActivity.RosterUser> cachedRoster = new ArrayList<>();
    private static String cachedClassroomTitle = "数字化课堂 [讨论频道]";
    private static boolean cachedGlobalMuted = false;
    private static boolean cachedScreenLocked = false;

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

    public static synchronized void dispatchAttendanceLogs(String date, JSONArray logs, JSONArray activeDates) {
        if (activeListener != null) {
            activeListener.onAttendanceLogsReceived(date, logs, activeDates);
        }
    }

    public static synchronized void setGlobalMuted(boolean isMuted) {
        cachedGlobalMuted = isMuted;
    }

    public static synchronized boolean isGlobalMuted() {
        return cachedGlobalMuted;
    }

    public static synchronized void setScreenLocked(boolean isLocked) {
        cachedScreenLocked = isLocked;
    }

    public static synchronized boolean isScreenLocked() {
        return cachedScreenLocked;
    }

    public static synchronized boolean isUserIndividuallyMuted(String username) {
        if (username == null || username.isEmpty()) return false;
        for (MainActivity.RosterUser u : cachedRoster) {
            if (username.equals(u.name)) {
                return u.isMuted;
            }
        }
        return false;
    }

    public static synchronized String getUserRoleFromRoster(String username, String fallbackRole) {
        if (username == null || username.isEmpty()) return fallbackRole;
        for (MainActivity.RosterUser u : cachedRoster) {
            if (username.equals(u.name) && u.role != null && !u.role.isEmpty()) {
                return u.role;
            }
        }
        return fallbackRole;
    }

    public static synchronized List<MainActivity.RosterUser> getCachedRoster() {
        return new ArrayList<>(cachedRoster);
    }

    public static synchronized String getCachedClassroomTitle() {
        return cachedClassroomTitle;
    }
}