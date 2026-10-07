package cn.edu.hbuas.coursetest;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import androidx.appcompat.widget.AppCompatImageView;

public class CropImageView extends AppCompatImageView {

    private Bitmap sourceBitmap;
    private final Matrix matrix = new Matrix();
    private final Matrix savedMatrix = new Matrix();

    private static final int NONE = 0;
    private static final int DRAG = 1;
    private static final int ZOOM = 2;
    private int mode = NONE;

    private final PointF start = new PointF();
    private final PointF mid = new PointF();
    private float oldDist = 1f;

    // 屏幕中央固定的 1:1 裁剪框
    private final RectF cropRect = new RectF();
    private final Paint maskPaint = new Paint();
    private final Paint borderPaint = new Paint();

    // 缓存矩阵值以进行限位运算
    private final float[] matrixValues = new float[9];

    public CropImageView(Context context) {
        super(context);
        init();
    }

    public CropImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public CropImageView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setScaleType(ScaleType.MATRIX);

        // 裁剪框外的暗色遮罩
        maskPaint.setColor(Color.parseColor("#B3000000"));
        maskPaint.setStyle(Paint.Style.FILL);

        // 裁剪框白色细边线
        borderPaint.setColor(Color.WHITE);
        borderPaint.setStrokeWidth(3f);
        borderPaint.setStyle(Paint.Style.STROKE);
    }

    public void setSourceBitmap(Bitmap bitmap) {
        this.sourceBitmap = bitmap;
        setImageBitmap(bitmap);
        post(this::initMatrix);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        int width = getWidth();
        int height = getHeight();
        if (width > 0 && height > 0) {
            // 计算屏幕中心正方形裁剪框（占用短边的 80%）
            int cropSize = (int) (Math.min(width, height) * 0.8f);
            float cropLeft = (width - cropSize) / 2f;
            float cropTop = (height - cropSize) / 2f;
            cropRect.set(cropLeft, cropTop, cropLeft + cropSize, cropTop + cropSize);
            if (sourceBitmap != null) {
                initMatrix();
            }
        }
    }

    /**
     * 初始加载：居中并铺满裁剪框
     */
    private void initMatrix() {
        if (sourceBitmap == null || cropRect.width() == 0) return;

        matrix.reset();
        int bmW = sourceBitmap.getWidth();
        int bmH = sourceBitmap.getHeight();

        // 初始缩放比例：确保短边刚好充满裁剪框
        float minScale = Math.max(cropRect.width() / bmW, cropRect.height() / bmH);
        matrix.postScale(minScale, minScale);

        // 初始平移：使图片正中心与裁剪框中心重合
        float dx = cropRect.centerX() - (bmW * minScale) / 2f;
        float dy = cropRect.centerY() - (bmH * minScale) / 2f;
        matrix.postTranslate(dx, dy);

        setImageMatrix(matrix);
        checkBoundsAndLimit();
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction() & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_DOWN:
                savedMatrix.set(matrix);
                start.set(event.getX(), event.getY());
                mode = DRAG;
                break;

            case MotionEvent.ACTION_POINTER_DOWN:
                oldDist = spacing(event);
                if (oldDist > 10f) {
                    savedMatrix.set(matrix);
                    midPoint(mid, event);
                    mode = ZOOM;
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                mode = NONE;
                // 手指离开时再次执行严格边界校验与平滑回正
                checkBoundsAndLimit();
                break;

            case MotionEvent.ACTION_MOVE:
                if (mode == DRAG) {
                    matrix.set(savedMatrix);
                    matrix.postTranslate(event.getX() - start.x, event.getY() - start.y);
                    checkBoundsAndLimit();
                } else if (mode == ZOOM) {
                    float newDist = spacing(event);
                    if (newDist > 10f) {
                        matrix.set(savedMatrix);
                        float scale = newDist / oldDist;
                        matrix.postScale(scale, scale, mid.x, mid.y);
                        checkBoundsAndLimit();
                    }
                }
                break;
        }

        setImageMatrix(matrix);
        return true;
    }

    /**
     * 限位器核心算法：限制图片缩放与平移，确保裁剪框永远不脱离图片内部
     */
    private void checkBoundsAndLimit() {
        if (sourceBitmap == null || cropRect.width() == 0) return;

        // 1. 获取当前图片的缩放倍率
        matrix.getValues(matrixValues);
        float currentScale = matrixValues[Matrix.MSCALE_X];

        int bmW = sourceBitmap.getWidth();
        int bmH = sourceBitmap.getHeight();

        // 最小允许缩放比：图片长宽均不能小于裁剪框长宽
        float minScale = Math.max(cropRect.width() / bmW, cropRect.height() / bmH);
        // 最大放大倍率（限制 5 倍，避免失真过度）
        float maxScale = minScale * 5.0f;

        if (currentScale < minScale) {
            float correctionRatio = minScale / currentScale;
            matrix.postScale(correctionRatio, correctionRatio, cropRect.centerX(), cropRect.centerY());
        } else if (currentScale > maxScale) {
            float correctionRatio = maxScale / currentScale;
            matrix.postScale(correctionRatio, correctionRatio, cropRect.centerX(), cropRect.centerY());
        }

        // 2. 获取经过缩放后图片当前的屏幕映射矩形
        RectF imageRect = new RectF(0, 0, bmW, bmH);
        matrix.mapRect(imageRect);

        // 3. 水平与垂直平移限位
        float deltaX = 0f;
        float deltaY = 0f;

        // 水平方向限位：左侧不能右于裁剪框左边缘，右侧不能左于裁剪框右边缘
        if (imageRect.left > cropRect.left) {
            deltaX = cropRect.left - imageRect.left;
        } else if (imageRect.right < cropRect.right) {
            deltaX = cropRect.right - imageRect.right;
        }

        // 垂直方向限位：顶部不能低于裁剪框上边缘，底部不能高于裁剪框下边缘
        if (imageRect.top > cropRect.top) {
            deltaY = cropRect.top - imageRect.top;
        } else if (imageRect.bottom < cropRect.bottom) {
            deltaY = cropRect.bottom - imageRect.bottom;
        }

        // 应用位移回正
        if (deltaX != 0 || deltaY != 0) {
            matrix.postTranslate(deltaX, deltaY);
        }
    }

    private float spacing(MotionEvent event) {
        float x = event.getX(0) - event.getX(1);
        float y = event.getY(0) - event.getY(1);
        return (float) Math.sqrt(x * x + y * y);
    }

    private void midPoint(PointF point, MotionEvent event) {
        float x = event.getX(0) + event.getX(1);
        float y = event.getY(0) + event.getY(1);
        point.set(x / 2, y / 2);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (cropRect.width() <= 0) return;

        // 绘制裁剪框四周遮罩
        canvas.drawRect(0, 0, getWidth(), cropRect.top, maskPaint);
        canvas.drawRect(0, cropRect.top, cropRect.left, cropRect.bottom, maskPaint);
        canvas.drawRect(cropRect.right, cropRect.top, getWidth(), cropRect.bottom, maskPaint);
        canvas.drawRect(0, cropRect.bottom, getWidth(), getHeight(), maskPaint);

        // 绘制白色边框
        canvas.drawRect(cropRect, borderPaint);
    }

    /**
     * 截取裁剪框内部像素（严格与裁剪框像素对齐，确保输出 120x120）
     */
    public Bitmap getCroppedBitmap() {
        if (sourceBitmap == null) return null;

        Matrix inverse = new Matrix();
        matrix.invert(inverse);

        RectF r = new RectF();
        inverse.mapRect(r, cropRect);

        int left = Math.max(0, (int) r.left);
        int top = Math.max(0, (int) r.top);
        int width = Math.min((int) r.width(), sourceBitmap.getWidth() - left);
        int height = Math.min((int) r.height(), sourceBitmap.getHeight() - top);

        if (width <= 0 || height <= 0) return null;

        Bitmap cropped = Bitmap.createBitmap(sourceBitmap, left, top, width, height);
        return Bitmap.createScaledBitmap(cropped, 120, 120, true);
    }
}