package com.bydwatch.companion.ui;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MaxHeightNestedScrollViewTest {

    @Test
    public void capsAHeightAtAFractionOfTheScreen() {
        Context context = RuntimeEnvironment.getApplication();
        MaxHeightNestedScrollView view = new MaxHeightNestedScrollView(context);
        TextView child = new TextView(context);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 500; i++) text.append("line ").append(i).append('\n');
        child.setText(text.toString());
        view.addView(child);

        view.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(4000, View.MeasureSpec.AT_MOST));

        int cap = (int) (context.getResources().getDisplayMetrics().heightPixels * 0.45f);
        assertTrue("measured=" + view.getMeasuredHeight() + " cap=" + cap,
                view.getMeasuredHeight() <= cap);
    }
}
