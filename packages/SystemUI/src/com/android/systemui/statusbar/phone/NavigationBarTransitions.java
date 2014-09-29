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

import android.animation.Animator;
import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.database.ContentObserver;
import android.graphics.PorterDuff;
import android.net.Uri;
import android.os.Handler;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.widget.ImageView;

import com.android.internal.statusbar.IStatusBarService;
import com.android.systemui.R;
import com.android.systemui.statusbar.policy.KeyButtonView;

public final class NavigationBarTransitions extends BarTransitions {

    private static final float KEYGUARD_QUIESCENT_ALPHA = 0.5f;
    private static final int CONTENT_FADE_DURATION = 200;

    private final NavigationBarView mView;
    private final IStatusBarService mBarService;

    private boolean mLightsOut;
    private boolean mVertical;
    private int mRequestedMode;
    private boolean mStickyTransparent;

    public NavigationBarTransitions(NavigationBarView view) {
        super(view, new NavigationBarBackgroundDrawable(view.getContext()));
        mView = view;
        mBarService = IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE));
    }

    public void init(boolean isVertical) {
        setVertical(isVertical);
        applyModeBackground(-1, getMode(), false /*animate*/);
        applyMode(getMode(), false /*animate*/, true /*force*/);
    }

    public void setVertical(boolean isVertical) {
        mVertical = isVertical;
        transitionTo(mRequestedMode, false /*animate*/);
    }

    @Override
    public void transitionTo(int mode, boolean animate) {
        mRequestedMode = mode;
        if (mVertical && mode == MODE_TRANSLUCENT) {
            // translucent mode not allowed when vertical
            mode = MODE_OPAQUE;
        } else if (mStickyTransparent) {
            mode = MODE_TRANSPARENT;
        }
        super.transitionTo(mode, animate);
    }

    @Override
    protected void onTransition(int oldMode, int newMode, boolean animate) {
        super.onTransition(oldMode, newMode, animate);
        applyMode(newMode, animate, false /*force*/);
    }

    private void applyMode(int mode, boolean animate, boolean force) {
        // apply to key buttons
        final float alpha = alphaForMode(mode);
        setKeyButtonViewQuiescentAlpha(mView.getHomeButton(), alpha, animate);
        setKeyButtonViewQuiescentAlpha(mView.getRecentsButton(), alpha, animate);
        setKeyButtonViewQuiescentAlpha(mView.getMenuButton(), alpha, animate);

        setKeyButtonViewQuiescentAlpha(mView.getSearchLight(), KEYGUARD_QUIESCENT_ALPHA, animate);
        setKeyButtonViewQuiescentAlpha(mView.getCameraButton(), KEYGUARD_QUIESCENT_ALPHA, animate);

        applyBackButtonQuiescentAlpha(mode, animate);

        // apply to lights out
        applyLightsOut(mode == MODE_LIGHTS_OUT, animate, force);
    }

    private float alphaForMode(int mode) {
        final boolean isOpaque = mode == MODE_OPAQUE || mode == MODE_LIGHTS_OUT;
        return isOpaque ? KeyButtonView.DEFAULT_QUIESCENT_ALPHA : 1f;
    }

    public void applyBackButtonQuiescentAlpha(int mode, boolean animate) {
        float backAlpha = 0;
        backAlpha = maxVisibleQuiescentAlpha(backAlpha, mView.getSearchLight());
        backAlpha = maxVisibleQuiescentAlpha(backAlpha, mView.getCameraButton());
        backAlpha = maxVisibleQuiescentAlpha(backAlpha, mView.getHomeButton());
        backAlpha = maxVisibleQuiescentAlpha(backAlpha, mView.getRecentsButton());
        backAlpha = maxVisibleQuiescentAlpha(backAlpha, mView.getMenuButton());
        if (backAlpha > 0) {
            setKeyButtonViewQuiescentAlpha(mView.getBackButton(), backAlpha, animate);
        }
    }

    public void applyTransparent(boolean sticky) {
        if (sticky != mStickyTransparent) {
            mStickyTransparent = sticky;
            if (!mStickyTransparent) {
                transitionTo(mRequestedMode, false);
            } else {
                transitionTo(MODE_TRANSPARENT, false);
            }
        }
    }

    private static float maxVisibleQuiescentAlpha(float max, View v) {
        if ((v instanceof KeyButtonView) && v.isShown()) {
            return Math.max(max, ((KeyButtonView)v).getQuiescentAlpha());
        }
        return max;
    }

    @Override
    public void setContentVisible(boolean visible) {
        final float alpha = visible ? 1 : 0;
        fadeContent(mView.getCameraButton(), alpha);
        fadeContent(mView.getSearchLight(), alpha);
    }

    private void fadeContent(View v, float alpha) {
        if (v != null) {
            v.animate().alpha(alpha).setDuration(CONTENT_FADE_DURATION);
        }
    }

    private void setKeyButtonViewQuiescentAlpha(View button, float alpha, boolean animate) {
        if (button instanceof KeyButtonView) {
            ((KeyButtonView) button).setQuiescentAlpha(alpha, animate);
        }
    }

    private void applyLightsOut(boolean lightsOut, boolean animate, boolean force) {
        if (!force && lightsOut == mLightsOut) return;

        mLightsOut = lightsOut;

        final View navButtons = mView.getCurrentView().findViewById(R.id.nav_buttons);
        final View lowLights = mView.getCurrentView().findViewById(R.id.lights_out);

        // ok, everyone, stop it right there
        navButtons.animate().cancel();
        lowLights.animate().cancel();

        final float navButtonsAlpha = lightsOut ? 0f : 1f;
        final float lowLightsAlpha = lightsOut ? 1f : 0f;

        if (!animate) {
            navButtons.setAlpha(navButtonsAlpha);
            lowLights.setAlpha(lowLightsAlpha);
            lowLights.setVisibility(lightsOut ? View.VISIBLE : View.GONE);
        } else {
            final int duration = lightsOut ? LIGHTS_OUT_DURATION : LIGHTS_IN_DURATION;
            navButtons.animate()
                .alpha(navButtonsAlpha)
                .setDuration(duration)
                .start();

            lowLights.setOnTouchListener(mLightsOutListener);
            if (lowLights.getVisibility() == View.GONE) {
                lowLights.setAlpha(0f);
                lowLights.setVisibility(View.VISIBLE);
            }
            lowLights.animate()
                .alpha(lowLightsAlpha)
                .setDuration(duration)
                .setInterpolator(new AccelerateInterpolator(2.0f))
                .setListener(lightsOut ? null : new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator _a) {
                        lowLights.setVisibility(View.GONE);
                    }
                })
                .start();
        }
    }

    private final View.OnTouchListener mLightsOutListener = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent ev) {
            if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                // even though setting the systemUI visibility below will turn these views
                // on, we need them to come up faster so that they can catch this motion
                // event
                applyLightsOut(false, false, false);

                try {
                    mBarService.setSystemUiVisibility(0, View.SYSTEM_UI_FLAG_LOW_PROFILE);
                } catch (android.os.RemoteException ex) {
                }
            }
            return false;
        }
    };

    protected static class NavigationBarBackgroundDrawable extends BarBackgroundDrawable {
        private final Context mContext;

        private int mOverrideColor = 0;
        private int mGradientAlpha = 0;

        public NavigationBarBackgroundDrawable(final Context context) {
            super(context, R.drawable.nav_background, R.color.navigation_bar_background_opaque,
                R.color.navigation_bar_background_semi_transparent);

            mContext = context;

            final GradientObserver obs = new GradientObserver(this, new Handler());
            (mContext.getContentResolver()).registerContentObserver(
                    GradientObserver.DYNAMIC_SYSTEM_BARS_GRADIENT_URI,
                    false, obs, UserHandle.USER_ALL);

            mGradientAlpha = Settings.System.getInt(mContext.getContentResolver(),
                    Settings.System.DYNAMIC_SYSTEM_BARS_GRADIENT_STATE, 0) == 1 ?
                            0xff : 0;

            BarBackgroundUpdater.addListener(new BarBackgroundUpdater.UpdateListener(this) {

                @Override
                public Animator onUpdateNavigationBarColor(final int previousColor, final int color) {
                    mOverrideColor = color;
                    startAnimation(25);
                    return null; // TODO return the animator
                }

            });
            BarBackgroundUpdater.init(context);
        }

        @Override
        protected int getColorOpaque() {
            return mOverrideColor == 0 ? super.getColorOpaque() : mOverrideColor;
        }

        @Override
        protected int getColorSemiTransparent() {
            return mOverrideColor == 0 ? super.getColorSemiTransparent() :
                    (mOverrideColor & 0x00ffffff | 0x7f000000);
        }

        @Override
        protected int getGradientAlphaOpaque() {
            return mGradientAlpha;
        }

        @Override
        protected int getGradientAlphaSemiTransparent() {
            return mGradientAlpha & 0x7f;
        }

        public void setGradientAlpha(final int alpha) {
            mGradientAlpha = alpha;
            startAnimation(25);
        }
    }

    private static final class GradientObserver extends ContentObserver {
        private static final Uri DYNAMIC_SYSTEM_BARS_GRADIENT_URI = Settings.System.getUriFor(
                Settings.System.DYNAMIC_SYSTEM_BARS_GRADIENT_STATE);

        private final NavigationBarBackgroundDrawable mDrawable;

        private GradientObserver(final NavigationBarBackgroundDrawable drawable,
                final Handler handler) {
            super(handler);
            mDrawable = drawable;
        }

        @Override
        public void onChange(final boolean selfChange) {
            mDrawable.setGradientAlpha(Settings.System.getInt(
                    mDrawable.mContext.getContentResolver(),
                    Settings.System.DYNAMIC_SYSTEM_BARS_GRADIENT_STATE, 0) == 1 ? 0xff : 0);
        }
    }
}
