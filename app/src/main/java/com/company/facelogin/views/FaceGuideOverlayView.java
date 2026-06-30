package com.company.facelogin.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

public class FaceGuideOverlayView extends View {

    public enum FaceState { SEARCHING, INSIDE, PASS }

    private final Paint overlayPaint = new Paint();
    private final Paint borderPaint  = new Paint();
    private final Paint clearPaint   = new Paint();
    private final RectF ovalRect     = new RectF();

    private FaceState faceState = FaceState.SEARCHING;

    public FaceGuideOverlayView(Context context) {
        super(context);
        init();
    }

    public FaceGuideOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        // Software layer required for PorterDuff.Mode.CLEAR to work
        setLayerType(LAYER_TYPE_SOFTWARE, null);

        overlayPaint.setColor(Color.argb(160, 0, 0, 0));
        overlayPaint.setStyle(Paint.Style.FILL);

        clearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        clearPaint.setAntiAlias(true);

        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(6f);
        borderPaint.setAntiAlias(true);
        borderPaint.setColor(Color.WHITE);
    }

    public void setFaceState(FaceState state) {
        if (this.faceState == state) return;
        this.faceState = state;
        switch (state) {
            case INSIDE:
            case PASS:
                borderPaint.setColor(Color.parseColor("#4CAF50")); // green
                break;
            default:
                borderPaint.setColor(Color.WHITE);
                break;
        }
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        float ovalW = w * 0.78f;
        float ovalH = ovalW * 1.4f;
        // Cap height so it fits within the view
        if (ovalH > h * 0.88f) {
            ovalH = h * 0.88f;
            ovalW = ovalH / 1.4f;
        }
        float cx = w / 2f;
        float cy = h / 2f;
        ovalRect.set(cx - ovalW / 2f, cy - ovalH / 2f, cx + ovalW / 2f, cy + ovalH / 2f);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawRect(0, 0, getWidth(), getHeight(), overlayPaint);
        canvas.drawOval(ovalRect, clearPaint);
        canvas.drawOval(ovalRect, borderPaint);
    }

    /** Oval bounds in this view's coordinate space (defensive copy). */
    public RectF getOvalRect() {
        return new RectF(ovalRect);
    }
}
