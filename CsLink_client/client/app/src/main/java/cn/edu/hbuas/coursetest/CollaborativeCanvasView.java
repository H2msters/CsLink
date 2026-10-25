package cn.edu.hbuas.coursetest;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public class CollaborativeCanvasView extends View {

    public interface OnStrokeCompletedListener {
        void onStrokeCompleted(JSONObject strokeJson);
    }

    private static class StrokeItem {
        Path path;
        Paint paint;

        StrokeItem(Path path, Paint paint) {
            this.path = path;
            this.paint = paint;
        }
    }

    private final List<StrokeItem> strokeList = new ArrayList<>();
    private final List<JSONObject> rawStrokeCache = new ArrayList<>();

    private Path currentPath;
    private Paint currentPaint;
    private final List<float[]> currentNormalizedPoints = new ArrayList<>();

    private String currentColorHex = "#1E293B";
    private float currentStrokeWidthDp = 4f;
    private boolean isEraserMode = false;

    private OnStrokeCompletedListener strokeListener;

    public CollaborativeCanvasView(Context context) {
        super(context);
        init();
    }

    public CollaborativeCanvasView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public CollaborativeCanvasView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setBackgroundColor(Color.WHITE);
        currentPaint = createPaint(currentColorHex, currentStrokeWidthDp);
    }

    public void setOnStrokeCompletedListener(OnStrokeCompletedListener listener) {
        this.strokeListener = listener;
    }

    public void setPenColor(String colorHex) {
        this.isEraserMode = false;
        this.currentColorHex = colorHex;
        this.currentStrokeWidthDp = 4f;
    }

    public void setEraserMode(boolean eraser) {
        this.isEraserMode = eraser;
        if (eraser) {
            this.currentColorHex = "#FFFFFF";
            this.currentStrokeWidthDp = 18f;
        }
    }

    private Paint createPaint(String colorHex, float widthDp) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        try {
            paint.setColor(Color.parseColor(colorHex));
        } catch (Exception e) {
            paint.setColor(Color.BLACK);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeCap(Paint.Cap.ROUND);
        float density = getResources().getDisplayMetrics().density;
        paint.setStrokeWidth(widthDp * density);
        return paint;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0 && !rawStrokeCache.isEmpty()) {
            rebuildStrokesFromCache(w, h);
        }
    }

    private void rebuildStrokesFromCache(int w, int h) {
        strokeList.clear();
        for (JSONObject strokeObj : rawStrokeCache) {
            StrokeItem item = parseStrokeJsonToItem(strokeObj, w, h);
            if (item != null) {
                strokeList.add(item);
            }
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        for (StrokeItem item : strokeList) {
            canvas.drawPath(item.path, item.paint);
        }
        if (currentPath != null && currentPaint != null) {
            canvas.drawPath(currentPath, currentPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return false;

        float x = Math.max(0, Math.min(w, event.getX()));
        float y = Math.max(0, Math.min(h, event.getY()));
        float nx = x / (float) w;
        float ny = y / (float) h;

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                String activeColor = isEraserMode ? "#FFFFFF" : currentColorHex;
                float activeWidth = isEraserMode ? 18f : currentStrokeWidthDp;
                currentPaint = createPaint(activeColor, activeWidth);
                currentPath = new Path();
                currentPath.moveTo(x, y);
                currentNormalizedPoints.clear();
                currentNormalizedPoints.add(new float[]{nx, ny});
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (currentPath != null) {
                    currentPath.lineTo(x, y);
                    currentNormalizedPoints.add(new float[]{nx, ny});
                    invalidate();
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (currentPath != null && currentPaint != null) {
                    currentPath.lineTo(x, y);
                    currentNormalizedPoints.add(new float[]{nx, ny});
                    strokeList.add(new StrokeItem(currentPath, currentPaint));

                    JSONObject strokeJson = buildStrokeJson();
                    if (strokeJson != null) {
                        rawStrokeCache.add(strokeJson);
                        if (strokeListener != null) {
                            strokeListener.onStrokeCompleted(strokeJson);
                        }
                    }
                    currentPath = null;
                    invalidate();
                }
                return true;
        }
        return false;
    }

    private JSONObject buildStrokeJson() {
        try {
            JSONObject obj = new JSONObject();
            obj.put("color", isEraserMode ? "#FFFFFF" : currentColorHex);
            obj.put("width", isEraserMode ? 18 : (int) currentStrokeWidthDp);
            JSONArray ptsArr = new JSONArray();
            for (float[] pt : currentNormalizedPoints) {
                JSONArray p = new JSONArray();
                p.put(Math.round(pt[0] * 10000f) / 10000.0);
                p.put(Math.round(pt[1] * 10000f) / 10000.0);
                ptsArr.put(p);
            }
            obj.put("points", ptsArr);
            return obj;
        } catch (JSONException e) {
            return null;
        }
    }

    public void loadHistoryStrokes(JSONArray strokesArr) {
        rawStrokeCache.clear();
        strokeList.clear();
        if (strokesArr != null) {
            int w = getWidth();
            int h = getHeight();
            for (int i = 0; i < strokesArr.length(); i++) {
                JSONObject s = strokesArr.optJSONObject(i);
                if (s != null) {
                    rawStrokeCache.add(s);
                    if (w > 0 && h > 0) {
                        StrokeItem item = parseStrokeJsonToItem(s, w, h);
                        if (item != null) strokeList.add(item);
                    }
                }
            }
        }
        invalidate();
    }

    public void addRemoteStroke(JSONObject strokeObj) {
        if (strokeObj == null) return;
        rawStrokeCache.add(strokeObj);
        int w = getWidth();
        int h = getHeight();
        if (w > 0 && h > 0) {
            StrokeItem item = parseStrokeJsonToItem(strokeObj, w, h);
            if (item != null) {
                strokeList.add(item);
                invalidate();
            }
        }
    }

    private StrokeItem parseStrokeJsonToItem(JSONObject strokeObj, int w, int h) {
        JSONArray pts = strokeObj.optJSONArray("points");
        if (pts == null || pts.length() == 0) return null;

        String color = strokeObj.optString("color", "#1E293B");
        float widthDp = (float) strokeObj.optDouble("width", 4.0);
        Paint paint = createPaint(color, widthDp);
        Path path = new Path();

        for (int i = 0; i < pts.length(); i++) {
            JSONArray pt = pts.optJSONArray(i);
            if (pt != null && pt.length() >= 2) {
                float px = (float) pt.optDouble(0, 0) * w;
                float py = (float) pt.optDouble(1, 0) * h;
                if (i == 0) {
                    path.moveTo(px, py);
                } else {
                    path.lineTo(px, py);
                }
            }
        }
        return new StrokeItem(path, paint);
    }

    public void clearCanvas() {
        rawStrokeCache.clear();
        strokeList.clear();
        currentPath = null;
        invalidate();
    }

    public Bitmap exportToBitmap() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return null;
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        draw(canvas);
        return bitmap;
    }
}