package cn.edu.hbuas.coursetest;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ChatAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public interface OnInteractiveCardClickListener {
        void onCardClick(String cardType, String title);
    }

    public interface OnImageClickListener {
        void onImageClick(String imageBase64);
    }

    private static final int TYPE_MESSAGE = 1;
    private static final int TYPE_CARD = 2;

    private final List<ChatMessage> messageList;
    private final Map<String, String> userAvatarMap;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private OnInteractiveCardClickListener cardClickListener;
    private OnImageClickListener imageClickListener;

    public ChatAdapter(List<ChatMessage> messageList, Map<String, String> userAvatarMap) {
        this.messageList = messageList;
        this.userAvatarMap = userAvatarMap;
    }

    public void setOnInteractiveCardClickListener(OnInteractiveCardClickListener listener) {
        this.cardClickListener = listener;
    }

    public void setOnImageClickListener(OnImageClickListener listener) {
        this.imageClickListener = listener;
    }

    @Override
    public int getItemViewType(int position) {
        ChatMessage msg = messageList.get(position);
        String type = msg.getType();
        if ("card_buzz".equals(type) || "card_vote".equals(type) || "card_rps".equals(type) || "card_canvas".equals(type)) {
            return TYPE_CARD;
        }
        return TYPE_MESSAGE;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_CARD) {
            View view = inflater.inflate(R.layout.item_chat_card, parent, false);
            return new CardViewHolder(view);
        }
        View view = inflater.inflate(R.layout.item_chat_message, parent, false);
        return new MessageViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        ChatMessage msg = messageList.get(position);

        if (holder instanceof CardViewHolder) {
            bindCardView((CardViewHolder) holder, msg);
        } else if (holder instanceof MessageViewHolder) {
            bindMessageView((MessageViewHolder) holder, msg);
        }
    }

    private void bindCardView(CardViewHolder vh, ChatMessage msg) {
        vh.tvCardTitle.setText(msg.getContent());
        vh.tvCardTime.setText(timeFormat.format(new Date(msg.getTimestamp())));

        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setCornerRadius(6f);

        if ("card_buzz".equals(msg.getType())) {
            vh.tvCardBadge.setText("班级抢答");
            vh.tvCardBadge.setTextColor(Color.parseColor("#DC2626"));
            badgeBg.setColor(Color.parseColor("#FEE2E2"));
            vh.btnEnterCardAction.setText("参与抢答");
        } else if ("card_vote".equals(msg.getType())) {
            vh.tvCardBadge.setText("班级投票");
            vh.tvCardBadge.setTextColor(Color.parseColor("#15803D"));
            badgeBg.setColor(Color.parseColor("#DCFCE7"));
            vh.btnEnterCardAction.setText("参与投票");
        } else if ("card_rps".equals(msg.getType())) {
            vh.tvCardBadge.setText("小组猜拳");
            vh.tvCardBadge.setTextColor(Color.parseColor("#0369A1"));
            badgeBg.setColor(Color.parseColor("#E0F2FE"));
            vh.btnEnterCardAction.setText("进入猜拳");
        } else if ("card_canvas".equals(msg.getType())) {
            vh.tvCardBadge.setText("协同白板");
            vh.tvCardBadge.setTextColor(Color.parseColor("#15803D"));
            badgeBg.setColor(Color.parseColor("#DCFCE7"));
            vh.btnEnterCardAction.setText("进入白板");
        }
        vh.tvCardBadge.setBackground(badgeBg);

        View.OnClickListener clickListener = v -> {
            if (cardClickListener != null) {
                cardClickListener.onCardClick(msg.getType(), msg.getContent());
            }
        };
        vh.btnEnterCardAction.setOnClickListener(clickListener);
        vh.layoutCardContent.setOnClickListener(clickListener);
    }

    private void bindMessageView(MessageViewHolder vh, ChatMessage msg) {
        String type = msg.getType();
        String timeStr = timeFormat.format(new Date(msg.getTimestamp()));

        if ("system".equals(type)) {
            vh.tvSystemNotice.setVisibility(View.VISIBLE);
            vh.tvSystemNotice.setText(msg.getContent());
            vh.layoutOtherMsg.setVisibility(View.GONE);
            vh.layoutMyMsg.setVisibility(View.GONE);
            return;
        }

        vh.tvSystemNotice.setVisibility(View.GONE);

        if (msg.isMe()) {
            vh.layoutMyMsg.setVisibility(View.VISIBLE);
            vh.layoutOtherMsg.setVisibility(View.GONE);

            vh.tvMySender.setText(msg.getSender());
            vh.tvMyTime.setText(timeStr);
            bindRoleBadge(vh.tvMyRoleBadge, msg.getRole());
            renderAvatar(vh.ivMyAvatar, msg.getSender(), msg.getAvatar());

            if ("image".equals(type)) {
                vh.tvMyContent.setVisibility(View.GONE);
                vh.ivMyImage.setVisibility(View.VISIBLE);
                renderBase64Image(vh.ivMyImage, msg.getContent());
                vh.ivMyImage.setOnClickListener(v -> {
                    if (imageClickListener != null && !TextUtils.isEmpty(msg.getContent())) {
                        imageClickListener.onImageClick(msg.getContent());
                    }
                });
            } else {
                vh.ivMyImage.setVisibility(View.GONE);
                vh.tvMyContent.setVisibility(View.VISIBLE);
                vh.tvMyContent.setText(msg.getContent());
            }
        } else {
            vh.layoutOtherMsg.setVisibility(View.VISIBLE);
            vh.layoutMyMsg.setVisibility(View.GONE);

            vh.tvOtherSender.setText(msg.getSender());
            vh.tvOtherTime.setText(timeStr);
            bindRoleBadge(vh.tvOtherRoleBadge, msg.getRole());
            renderAvatar(vh.ivOtherAvatar, msg.getSender(), msg.getAvatar());

            if ("image".equals(type)) {
                vh.tvOtherContent.setVisibility(View.GONE);
                vh.ivOtherImage.setVisibility(View.VISIBLE);
                renderBase64Image(vh.ivOtherImage, msg.getContent());
                vh.ivOtherImage.setOnClickListener(v -> {
                    if (imageClickListener != null && !TextUtils.isEmpty(msg.getContent())) {
                        imageClickListener.onImageClick(msg.getContent());
                    }
                });
            } else {
                vh.ivOtherImage.setVisibility(View.GONE);
                vh.tvOtherContent.setVisibility(View.VISIBLE);
                vh.tvOtherContent.setText(msg.getContent());
            }
        }
    }

    private void bindRoleBadge(TextView tvBadge, String role) {
        if ("teacher".equalsIgnoreCase(role)) {
            tvBadge.setVisibility(View.VISIBLE);
            tvBadge.setText("教师");
            tvBadge.setBackgroundColor(Color.parseColor("#FEF3C7"));
            tvBadge.setTextColor(Color.parseColor("#B45309"));
        } else if ("monitor".equalsIgnoreCase(role)) {
            tvBadge.setVisibility(View.VISIBLE);
            tvBadge.setText("班长");
            tvBadge.setBackgroundColor(Color.parseColor("#E0E7FF"));
            tvBadge.setTextColor(Color.parseColor("#4338CA"));
        } else {
            tvBadge.setVisibility(View.GONE);
        }
    }

    private void renderAvatar(ImageView iv, String sender, String avatarBase64) {
        String effectiveAvatar = avatarBase64;
        if (TextUtils.isEmpty(effectiveAvatar) && userAvatarMap != null) {
            effectiveAvatar = userAvatarMap.get(sender);
        }
        if (!TextUtils.isEmpty(effectiveAvatar)) {
            try {
                byte[] bytes = Base64.decode(effectiveAvatar, Base64.DEFAULT);
                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bitmap != null) {
                    iv.setImageBitmap(bitmap);
                    return;
                }
            } catch (Exception ignored) {}
        }
        iv.setImageResource(R.mipmap.ic_launcher_round);
    }

    private void renderBase64Image(ImageView iv, String imgBase64) {
        if (!TextUtils.isEmpty(imgBase64)) {
            try {
                byte[] bytes = Base64.decode(imgBase64, Base64.DEFAULT);
                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bitmap != null) {
                    iv.setImageBitmap(bitmap);
                    return;
                }
            } catch (Exception ignored) {}
        }
    }

    @Override
    public int getItemCount() {
        return messageList.size();
    }

    static class CardViewHolder extends RecyclerView.ViewHolder {
        View layoutCardContent;
        TextView tvCardBadge;
        TextView tvCardTime;
        TextView tvCardTitle;
        Button btnEnterCardAction;

        CardViewHolder(View itemView) {
            super(itemView);
            layoutCardContent = itemView.findViewById(R.id.layoutCardContent);
            tvCardBadge = itemView.findViewById(R.id.tvCardBadge);
            tvCardTime = itemView.findViewById(R.id.tvCardTime);
            tvCardTitle = itemView.findViewById(R.id.tvCardTitle);
            btnEnterCardAction = itemView.findViewById(R.id.btnEnterCardAction);
        }
    }

    static class MessageViewHolder extends RecyclerView.ViewHolder {
        TextView tvSystemNotice;

        LinearLayout layoutOtherMsg;
        ImageView ivOtherAvatar;
        TextView tvOtherSender;
        TextView tvOtherRoleBadge;
        TextView tvOtherTime;
        TextView tvOtherContent;
        ImageView ivOtherImage;

        LinearLayout layoutMyMsg;
        TextView tvMyTime;
        TextView tvMyRoleBadge;
        TextView tvMySender;
        TextView tvMyContent;
        ImageView ivMyImage;
        ImageView ivMyAvatar;

        MessageViewHolder(View itemView) {
            super(itemView);
            tvSystemNotice = itemView.findViewById(R.id.tvSystemNotice);

            layoutOtherMsg = itemView.findViewById(R.id.layoutOtherMsg);
            ivOtherAvatar = itemView.findViewById(R.id.ivOtherAvatar);
            tvOtherSender = itemView.findViewById(R.id.tvOtherSender);
            tvOtherRoleBadge = itemView.findViewById(R.id.tvOtherRoleBadge);
            tvOtherTime = itemView.findViewById(R.id.tvOtherTime);
            tvOtherContent = itemView.findViewById(R.id.tvOtherContent);
            ivOtherImage = itemView.findViewById(R.id.ivOtherImage);

            layoutMyMsg = itemView.findViewById(R.id.layoutMyMsg);
            tvMyTime = itemView.findViewById(R.id.tvMyTime);
            tvMyRoleBadge = itemView.findViewById(R.id.tvMyRoleBadge);
            tvMySender = itemView.findViewById(R.id.tvMySender);
            tvMyContent = itemView.findViewById(R.id.tvMyContent);
            ivMyImage = itemView.findViewById(R.id.ivMyImage);
            ivMyAvatar = itemView.findViewById(R.id.ivMyAvatar);
        }
    }
}