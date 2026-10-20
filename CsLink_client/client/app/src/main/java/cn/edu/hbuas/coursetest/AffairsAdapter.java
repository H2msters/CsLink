package cn.edu.hbuas.coursetest;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.RecyclerView;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class AffairsAdapter extends RecyclerView.Adapter<AffairsAdapter.AffairViewHolder> {

    public interface OnAffairActionListener {
        void onConfirm(String affairId);
        void onViewUnreadList(AffairItem item, List<String> unreadStudents);
        void onEdit(AffairItem item);
        void onDelete(String affairId);
    }

    private final List<AffairItem> affairList;
    private final String currentUserName;
    private final String currentUserRole;
    private final OnAffairActionListener actionListener;

    public AffairsAdapter(List<AffairItem> affairList, String currentUserName,
                          String currentUserRole, OnAffairActionListener actionListener) {
        this.affairList = affairList;
        this.currentUserName = currentUserName;
        this.currentUserRole = currentUserRole;
        this.actionListener = actionListener;
    }

    @NonNull
    @Override
    public AffairViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_affair, parent, false);
        return new AffairViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull AffairViewHolder holder, int position) {
        AffairItem item = affairList.get(position);

        holder.tvAffairTitle.setText(item.getTitle());
        holder.tvAffairContent.setText(item.getContent());
        holder.tvAffairMeta.setText("发布人：" + item.getPublisher() + " · " + item.getFormattedTime());

        // 1. 类型标签颜色区分
        if ("homework".equalsIgnoreCase(item.getType())) {
            holder.tvAffairTypeBadge.setText("班级作业");
            holder.tvAffairTypeBadge.setBackgroundColor(Color.parseColor("#FEF3C7"));
            holder.tvAffairTypeBadge.setTextColor(Color.parseColor("#B45309"));
        } else {
            holder.tvAffairTypeBadge.setText("班级通知");
            holder.tvAffairTypeBadge.setBackgroundColor(Color.parseColor("#DBEAFE"));
            holder.tvAffairTypeBadge.setTextColor(Color.parseColor("#1E40AF"));
        }

        // 2. 截止时间解析与剩余时间动态渲染
        String deadlineStr = item.getDeadline();
        if (!TextUtils.isEmpty(deadlineStr)) {
            holder.tvAffairDeadline.setVisibility(View.VISIBLE);
            renderDeadlineToView(holder.tvAffairDeadline, deadlineStr);
        } else {
            holder.tvAffairDeadline.setVisibility(View.GONE);
        }

        // 3. 计算未读学生列表（全班登记学生 - 已读学生）
        List<MainActivity.RosterUser> cachedRoster = RosterDataBridge.getCachedRoster();
        List<String> unreadStudentList = new ArrayList<>();
        for (MainActivity.RosterUser user : cachedRoster) {
            if (!"teacher".equalsIgnoreCase(user.role)) {
                if (!item.isConfirmedBy(user.name)) {
                    unreadStudentList.add(user.name);
                }
            }
        }
        int unreadCount = unreadStudentList.size();

        // 4. 按钮角色逻辑
        boolean isTeacher = "teacher".equalsIgnoreCase(currentUserRole);
        boolean hasRead = item.isConfirmedBy(currentUserName);

        if (isTeacher) {
            holder.btnEditAffair.setVisibility(View.VISIBLE);
            holder.btnDeleteAffair.setVisibility(View.VISIBLE);

            if (unreadCount > 0) {
                holder.btnConfirmAffair.setText("未读 (" + unreadCount + ")");
                ViewCompat.setBackgroundTintList(holder.btnConfirmAffair, ColorStateList.valueOf(Color.parseColor("#EF4444")));
                holder.btnConfirmAffair.setTextColor(Color.WHITE);
            } else {
                holder.btnConfirmAffair.setText("全员已读");
                ViewCompat.setBackgroundTintList(holder.btnConfirmAffair, ColorStateList.valueOf(Color.parseColor("#10B981")));
                holder.btnConfirmAffair.setTextColor(Color.WHITE);
            }
            holder.btnConfirmAffair.setEnabled(true);

            holder.btnEditAffair.setOnClickListener(v -> {
                if (actionListener != null) actionListener.onEdit(item);
            });

            holder.btnDeleteAffair.setOnClickListener(v -> {
                if (actionListener != null) actionListener.onDelete(item.getAffairId());
            });

            holder.btnConfirmAffair.setOnClickListener(v -> {
                if (actionListener != null) actionListener.onViewUnreadList(item, unreadStudentList);
            });
        } else {
            holder.btnEditAffair.setVisibility(View.GONE);
            holder.btnDeleteAffair.setVisibility(View.GONE);

            if (hasRead) {
                // 已读：灰底灰字不可点
                holder.btnConfirmAffair.setText("已读");
                holder.btnConfirmAffair.setEnabled(false);
                ViewCompat.setBackgroundTintList(holder.btnConfirmAffair, ColorStateList.valueOf(Color.parseColor("#E5E7EB")));
                holder.btnConfirmAffair.setTextColor(Color.parseColor("#9CA3AF"));
            } else {
                // 未读：蓝底白字可交互
                holder.btnConfirmAffair.setText("标为已读");
                holder.btnConfirmAffair.setEnabled(true);
                ViewCompat.setBackgroundTintList(holder.btnConfirmAffair, ColorStateList.valueOf(Color.parseColor("#1E88E5")));
                holder.btnConfirmAffair.setTextColor(Color.WHITE);

                holder.btnConfirmAffair.setOnClickListener(v -> {
                    // 点击后即时变灰并禁用
                    holder.btnConfirmAffair.setText("已读");
                    holder.btnConfirmAffair.setEnabled(false);
                    ViewCompat.setBackgroundTintList(holder.btnConfirmAffair, ColorStateList.valueOf(Color.parseColor("#E5E7EB")));
                    holder.btnConfirmAffair.setTextColor(Color.parseColor("#9CA3AF"));
                    Toast.makeText(v.getContext(), "已完成阅读回执", Toast.LENGTH_SHORT).show();

                    if (actionListener != null) {
                        actionListener.onConfirm(item.getAffairId());
                    }
                });
            }
        }
    }

    /**
     * 计算截止时间差，并在 TextView 上展示剩余时间或灰色的已截止
     */
    private void renderDeadlineToView(TextView tvDeadline, String deadlineStr) {
        long deadlineTime = parseDeadlineTimestamp(deadlineStr);
        if (deadlineTime <= 0) {
            tvDeadline.setText("截止时间：" + deadlineStr);
            tvDeadline.setTextColor(Color.parseColor("#6B7280"));
            return;
        }

        long now = System.currentTimeMillis();
        long diff = deadlineTime - now;

        if (diff <= 0) {
            // 已过截止时间：显示灰色的已截止
            tvDeadline.setText("截止时间：" + deadlineStr + " (已截止)");
            tvDeadline.setTextColor(Color.parseColor("#9CA3AF"));
        } else {
            // 未过截止时间：动态计算剩余天、小时、分钟
            long minutes = diff / (1000 * 60);
            long hours = minutes / 60;
            long days = hours / 24;

            String remainingText;
            if (days > 0) {
                remainingText = "剩余 " + days + "天" + (hours % 24) + "小时";
            } else if (hours > 0) {
                remainingText = "剩余 " + hours + "小时" + (minutes % 60) + "分";
            } else {
                remainingText = "剩余 " + Math.max(1, minutes) + "分钟";
            }

            tvDeadline.setText("截止时间：" + deadlineStr + " (" + remainingText + ")");
            // 剩余不足 2 小时使用警示橙色，其余使用常规深灰色
            if (hours < 2 && days == 0) {
                tvDeadline.setTextColor(Color.parseColor("#EA580C"));
            } else {
                tvDeadline.setTextColor(Color.parseColor("#4B5563"));
            }
        }
    }

    private long parseDeadlineTimestamp(String deadlineStr) {
        try {
            int currentYear = Calendar.getInstance().get(Calendar.YEAR);
            String fullTimeStr;
            String pattern;

            if (deadlineStr.length() <= 11) { // 格式如: 10-20 18:00
                fullTimeStr = currentYear + "-" + deadlineStr.trim();
                pattern = "yyyy-MM-dd HH:mm";
            } else {
                fullTimeStr = deadlineStr.trim();
                pattern = "yyyy-MM-dd HH:mm";
            }

            SimpleDateFormat sdf = new SimpleDateFormat(pattern, Locale.getDefault());
            Date d = sdf.parse(fullTimeStr);
            return d != null ? d.getTime() : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    @Override
    public int getItemCount() {
        return affairList.size();
    }

    static class AffairViewHolder extends RecyclerView.ViewHolder {
        TextView tvAffairTypeBadge;
        TextView tvAffairTitle;
        TextView tvAffairMeta;
        TextView tvAffairDeadline;
        TextView tvAffairContent;
        Button btnEditAffair;
        Button btnDeleteAffair;
        Button btnConfirmAffair;

        public AffairViewHolder(@NonNull View itemView) {
            super(itemView);
            tvAffairTypeBadge = itemView.findViewById(R.id.tvAffairTypeBadge);
            tvAffairTitle = itemView.findViewById(R.id.tvAffairTitle);
            tvAffairMeta = itemView.findViewById(R.id.tvAffairMeta);
            tvAffairDeadline = itemView.findViewById(R.id.tvAffairDeadline);
            tvAffairContent = itemView.findViewById(R.id.tvAffairContent);
            btnEditAffair = itemView.findViewById(R.id.btnEditAffair);
            btnDeleteAffair = itemView.findViewById(R.id.btnDeleteAffair);
            btnConfirmAffair = itemView.findViewById(R.id.btnConfirmAffair);
        }
    }
}