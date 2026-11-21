package com.bydwatch.companion.ui;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;

/**
 * A {@link NestedScrollView} that never grows past a fraction of the screen. The update dialog
 * uses it so a long release changelog scrolls inside the dialog instead of pushing the download
 * button off the bottom of the screen.
 */
public class MaxHeightNestedScrollView extends NestedScrollView {

    private static final float MAX_SCREEN_FRACTION = 0.45f;

    public MaxHeightNestedScrollView(Context context) {
        super(context);
    }

    public MaxHeightNestedScrollView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public MaxHeightNestedScrollView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int cap = (int) (getResources().getDisplayMetrics().heightPixels * MAX_SCREEN_FRACTION);
        int mode = MeasureSpec.getMode(heightMeasureSpec);
        int limit = mode == MeasureSpec.UNSPECIFIED
                ? cap : Math.min(cap, MeasureSpec.getSize(heightMeasureSpec));
        super.onMeasure(widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST));
    }
}
