package com.storeme;

import android.app.Activity;
import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import eightbitlab.com.blurview.BlurView;
import eightbitlab.com.blurview.RenderScriptBlur;

public class GlassBlurView extends BlurView {

    public GlassBlurView(Context context) {
        super(context);
    }

    public GlassBlurView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public GlassBlurView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (getContext() instanceof Activity) {
            Activity activity = (Activity) getContext();
            ViewGroup rootView = activity.findViewById(android.R.id.content);
            if (rootView != null) {
                this.setupWith(rootView, new RenderScriptBlur(getContext()))
                    .setFrameClearDrawable(activity.getWindow().getDecorView().getBackground())
                    .setBlurRadius(20f);
            }
        }
    }
}
