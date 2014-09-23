/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.phone;

import android.animation.TimeInterpolator;
import android.app.ActivityManager;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.view.animation.LinearInterpolator;

import com.android.systemui.R;

public class BarTransitions {
    private static final boolean DEBUG = false;
    private static final boolean DEBUG_COLORS = false;

    public static final boolean HIGH_END = ActivityManager.isHighEndGfx();

    public static final int MODE_OPAQUE = 0;
    public static final int MODE_SEMI_TRANSPARENT = 1;
    public static final int MODE_TRANSLUCENT = 2;
    public static final int MODE_LIGHTS_OUT = 3;
    public static final int MODE_TRANSPARENT = 4;

    public static final int LIGHTS_IN_DURATION = 250;
    public static final int LIGHTS_OUT_DURATION = 750;
    public static final int BACKGROUND_DURATION = 200;

    private static int mDSBDuration;

    private final String mTag;
    private final View mView;
    private final BarBackgroundDrawable mBarBackground;

    private int mMode;

    public BarTransitions(View view, BarBackgroundDrawable barBackground) {
        mTag = "BarTransitions." + view.getClass().getSimpleName();
        mView = view;
        mBarBackground = barBackground;
        if (HIGH_END) {
            mView.setBackground(mBarBackground);
        }
    }

    public void updateResources(Resources res) {
        mBarBackground.updateResources(res);
    }

    public int getMode() {
        return mMode;
    }

    public void transitionTo(int mode, boolean animate) {
        // low-end devices do not support translucent modes, fallback to opaque
        if (!HIGH_END && (mode == MODE_SEMI_TRANSPARENT || mode == MODE_TRANSLUCENT)) {
            mode = MODE_OPAQUE;
        }
        if (mMode == mode) return;
        int oldMode = mMode;
        mMode = mode;
        if (DEBUG) Log.d(mTag, String.format("%s -> %s animate=%s",
                modeToString(oldMode), modeToString(mode),  animate));
        onTransition(oldMode, mMode, animate);
    }

    protected void onTransition(int oldMode, int newMode, boolean animate) {
        if (HIGH_END) {
            applyModeBackground(oldMode, newMode, animate);
        }
    }

    protected void applyModeBackground(int oldMode, int newMode, boolean animate) {
        if (DEBUG) Log.d(mTag, String.format("applyModeBackground oldMode=%s newMode=%s animate=%s",
                modeToString(oldMode), modeToString(newMode), animate));
        mBarBackground.applyModeBackground(oldMode, newMode, animate);
    }

    public static String modeToString(int mode) {
        if (mode == MODE_OPAQUE) return "MODE_OPAQUE";
        if (mode == MODE_SEMI_TRANSPARENT) return "MODE_SEMI_TRANSPARENT";
        if (mode == MODE_TRANSLUCENT) return "MODE_TRANSLUCENT";
        if (mode == MODE_LIGHTS_OUT) return "MODE_LIGHTS_OUT";
        if (mode == MODE_TRANSPARENT) return "MODE_TRANSPARENT";
        if (DEBUG && mode == -1) return "-1";
        throw new IllegalArgumentException("Unknown mode " + mode);
    }

    public void finishAnimations() {
        mBarBackground.finishAnimation();
    }

    public void setContentVisible(boolean visible) {
        // for subclasses
    }

    public void applyTransparent(boolean sticky) {
        // for subclasses
    }

    protected static class BarBackgroundDrawable extends Drawable {
        private final int mGradientResourceId;
        private final int mOpaqueColorResourceId;
        private final int mSemiTransparentColorResourceId;
        private final TimeInterpolator mInterpolator;
        private final Handler mHandler;
        private final Runnable mInvalidateSelf = new Runnable() {

            @Override
            public void run() {
                invalidateSelf();
            }

        };

        private int mOpaque;
        private int mSemiTransparent;
        private Drawable mGradient;
        private int mMode = -1;
        private boolean mAnimating;
        private long mStartTime;
        private long mEndTime;

        private int mGradientAlpha;
        private int mColor;

        private int mGradientAlphaStart;
        private int mColorStart;

        public BarBackgroundDrawable(Context context, int gradientResourceId,
                int opaqueColorResourceId, int semiTransparentColorResourceId) {
            final Resources res = context.getResources();
            if (DEBUG_COLORS) {
                mOpaque = 0xff0000ff;
                mSemiTransparent = 0x7f0000ff;
            } else {
                mOpaque = res.getColor(opaqueColorResourceId);
                mSemiTransparent = res.getColor(semiTransparentColorResourceId);
            }
            mGradient = res.getDrawable(gradientResourceId);
            mInterpolator = new LinearInterpolator();
            mGradientResourceId = gradientResourceId;
            mOpaqueColorResourceId = opaqueColorResourceId;
            mSemiTransparentColorResourceId = semiTransparentColorResourceId;
            mHandler = new Handler();
            mDSBDuration = context.getResources().getInteger(R.integer.dsb_transition_duration);
        }

        protected int getColorOpaque() {
            return mOpaque;
        }

        protected int getColorSemiTransparent() {
            return mSemiTransparent;
        }

        protected int getGradientAlphaOpaque() {
            return 0;
        }

        protected int getGradientAlphaSemiTransparent() {
            return 0;
        }

        public void updateResources(Resources res)  {
            mOpaque = res.getColor(mOpaqueColorResourceId);
            mSemiTransparent = res.getColor(mSemiTransparentColorResourceId);
            // Retrieve the current bounds for mGradient so they can be set to
            // the new drawable being loaded, otherwise the bounds will be (0, 0, 0, 0)
            // and the gradient will not be drawn.
            Rect bounds = mGradient.getBounds();
            mGradient = res.getDrawable(mGradientResourceId);
            mGradient.setBounds(bounds);
        }

        @Override
        public void setAlpha(int alpha) {
            // noop
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            // noop
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            super.onBoundsChange(bounds);
            mGradient.setBounds(bounds);
        }

        public void applyModeBackground(int oldMode, int newMode, boolean animate) {
            if (mMode == newMode) return;
            mMode = newMode;
            mAnimating = animate;
            if (animate) {
                long now = SystemClock.elapsedRealtime();
                mStartTime = now + 50;
                mEndTime = mStartTime + BACKGROUND_DURATION;
                mGradientAlphaStart = mGradientAlpha;
                mColorStart = mColor;
            }
            mHandler.removeCallbacks(mInvalidateSelf);
            mHandler.postDelayed(mInvalidateSelf, 50);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        protected synchronized void startAnimation() {
            startAnimation(50);
        }

        protected synchronized void startAnimation(final int delay) {
            final long now = SystemClock.elapsedRealtime();

            if (!mAnimating || now >= mEndTime) {
                mGradientAlphaStart = mGradientAlpha;
                mColorStart = mColor;
            } else {
                final float t = Math.max(0, now - mStartTime) / (float)(mEndTime - mStartTime);
                final float v = Math.max(0, Math.min(mInterpolator.getInterpolation(t), 1));
                mGradientAlphaStart = (int)(v * mGradientAlpha + mGradientAlphaStart * (1 - v));
                mColorStart = Color.argb(
                      (int)(v * Color.alpha(mColor) + Color.alpha(mColorStart) * (1 - v)),
                      (int)(v * Color.red(mColor) + Color.red(mColorStart) * (1 - v)),
                      (int)(v * Color.green(mColor) + Color.green(mColorStart) * (1 - v)),
                      (int)(v * Color.blue(mColor) + Color.blue(mColorStart) * (1 - v)));
            }

            mStartTime = now + 50;
            mEndTime = mStartTime + BACKGROUND_DURATION;
            mAnimating = true;

            // only invalidate when we will need to use these colors for sure
            if (mMode == MODE_OPAQUE || mMode == MODE_LIGHTS_OUT ||
                    mMode == MODE_SEMI_TRANSPARENT) {
                mHandler.removeCallbacks(mInvalidateSelf);
                mHandler.postDelayed(mInvalidateSelf, delay);
            }
        }

        public void finishAnimation() {
            if (mAnimating) {
                mAnimating = false;
                mHandler.removeCallbacks(mInvalidateSelf);
                mHandler.postDelayed(mInvalidateSelf, 50);
            }
        }

        @Override
        public void draw(Canvas canvas) {
            int targetGradientAlpha = 0, targetColor = 0;
            if (mMode == MODE_TRANSLUCENT) {
                targetGradientAlpha = 0xff;
            } else if (mMode == MODE_SEMI_TRANSPARENT) {
                targetGradientAlpha = getGradientAlphaSemiTransparent();
                targetColor = getColorSemiTransparent();
            } else if (mMode == MODE_TRANSPARENT) {
                targetGradientAlpha = 0;
            } else {
                targetGradientAlpha = getGradientAlphaOpaque();
                targetColor = getColorOpaque();
            }
            if (!mAnimating) {
                mColor = targetColor;
                mGradientAlpha = targetGradientAlpha;
            } else {
                final long now = SystemClock.elapsedRealtime();
                if (now >= mEndTime) {
                    mAnimating = false;
                    mColor = targetColor;
                    mGradientAlpha = targetGradientAlpha;
                } else {
                    final float t = Math.max(0, now - mStartTime) / (float)(mEndTime - mStartTime);
                    final float v = Math.max(0, Math.min(mInterpolator.getInterpolation(t), 1));
                    mGradientAlpha = (int)(v * targetGradientAlpha + mGradientAlphaStart * (1 - v));
                    mColor = Color.argb(
                          (int)(v * Color.alpha(targetColor) + Color.alpha(mColorStart) * (1 - v)),
                          (int)(v * Color.red(targetColor) + Color.red(mColorStart) * (1 - v)),
                          (int)(v * Color.green(targetColor) + Color.green(mColorStart) * (1 - v)),
                          (int)(v * Color.blue(targetColor) + Color.blue(mColorStart) * (1 - v)));
                }
            }
            if (Color.alpha(mColor) > 0) {
                canvas.drawColor(mColor);
            }
            if (mGradientAlpha > 0) {
                mGradient.setAlpha(mGradientAlpha);
                mGradient.draw(canvas);
            }
            if (mAnimating) {
                // keep going
                mHandler.removeCallbacks(mInvalidateSelf);
                mHandler.postDelayed(mInvalidateSelf, 50);
            }
        }
    }
}
