package cn.edu.hbuas.coursetest;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class AffairItem {
    private String affairId;
    private String title;
    private String content;
    private String type;          // notice(通知) / homework(作业)
    private String publisher;
    private long timestamp;
    private int confirmedCount;
    private List<String> confirmedStudents;

    public AffairItem(String affairId, String title, String content, String type,
                      String publisher, long timestamp, int confirmedCount,
                      List<String> confirmedStudents) {
        this.affairId = affairId;
        this.title = title;
        this.content = content;
        this.type = (type == null || type.trim().isEmpty()) ? "notice" : type.trim();
        this.publisher = publisher;
        this.timestamp = timestamp > 0 ? timestamp : System.currentTimeMillis();
        this.confirmedCount = confirmedCount;
        this.confirmedStudents = (confirmedStudents != null) ? confirmedStudents : new ArrayList<>();
    }

    public String getAffairId() {
        return affairId;
    }

    public String getTitle() {
        return title;
    }

    public String getContent() {
        return content;
    }

    public String getType() {
        return type;
    }

    public String getPublisher() {
        return publisher;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public int getConfirmedCount() {
        return confirmedCount;
    }

    public List<String> getConfirmedStudents() {
        return confirmedStudents;
    }

    /**
     * 判断指定学生是否已经点击过该事务的确认回执
     */
    public boolean isConfirmedBy(String studentName) {
        if (studentName == null || confirmedStudents == null) {
            return false;
        }
        return confirmedStudents.contains(studentName);
    }

    public String getFormattedTime() {
        SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
        return sdf.format(new Date(timestamp));
    }
}