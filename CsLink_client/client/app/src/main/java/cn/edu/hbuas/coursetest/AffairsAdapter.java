package cn.edu.hbuas.coursetest;

import android.graphics.Color;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;

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

        // 2. 截止时间
        if (!TextUtils.isEmpty(item.getDeadline())) {
            holder.tvAffairDeadline.setVisibility(View.VISIBLE);
            holder.tvAffairDeadline.setText("截止时间：" + item.getDeadline());
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
                holder.btnConfirmAffair.setBackgroundColor(Color.parseColor("#EF4444")); // 红色提示有未读
            } else {
                holder.btnConfirmAffair.setText("全员已读");
                holder.btnConfirmAffair.setBackgroundColor(Color.parseColor("#10B981")); // 绿色代表全齐
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
                holder.btnConfirmAffair.setText("✓ 已读");
                holder.btnConfirmAffair.setEnabled(false);
                holder.btnConfirmAffair.setBackgroundColor(Color.parseColor("#9CA3AF"));
            } else {
                holder.btnConfirmAffair.setText("标为已读");
                holder.btnConfirmAffair.setEnabled(true);
                holder.btnConfirmAffair.setBackgroundColor(Color.parseColor("#1E88E5"));
                holder.btnConfirmAffair.setOnClickListener(v -> {
                    if (actionListener != null) actionListener.onConfirm(item.getAffairId());
                });
            }
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