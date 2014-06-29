/*
 * Copyright (C) 2010, The Android Open Source Project
 * Copyright (C) 2014, ParanoidAndroid Project.
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

package com.android.systemui.statusbar.pie;

import android.animation.Animator;
import android.animation.Animator.AnimatorListener;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.animation.TimeInterpolator;
import android.app.ActivityManagerNative;
import android.database.ContentObserver;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.Rect;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuff.Mode;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.LightingColorFilter;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.RemoteException;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.BounceInterpolator;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ScrollView;

import com.android.systemui.R;
import com.android.systemui.statusbar.policy.Clock;

import java.util.ArrayList;
import java.util.List;

import static com.android.systemui.statusbar.pie.PieController.RECENT_BUTTON;

/**
 * Pie menu
 * Handles creating, drawing, animations and touch eventing for pie.
 */
public class PieMenu extends FrameLayout {

    private static final String FONT_FAMILY_CONDENSED = "sans-serif-condensed";
    private static final String FONT_FAMILY_LIGHT = "sans-serif-light";

    private static final int IMMERSIVE_MODE_FULL = 1;
    private static final int IMMERSIVE_MODE_HIDE_NAVBAR_ONLY = 2;

    private static int ANIMATOR_DEC_SPEED15 = 1;
    private static int ANIMATOR_ACC_SPEED15 = 2;
    private static int ANIMATOR_ACC_INC_1 = ANIMATOR_ACC_SPEED15 + 1;
    private static int ANIMATOR_ACC_INC_15 = ANIMATOR_ACC_INC_1 + 15;
    private static int ANIMATOR_BATTERY_METER = ANIMATOR_ACC_INC_15 + 1;
    private static int ANIMATOR_SNAP_GROW = ANIMATOR_ACC_INC_15 + 2;
    private static int ANIMATOR_END = ANIMATOR_SNAP_GROW;
    private static final int COLOR_SNAP_BACKGROUND = 0xaaffffff;
    private static final int COLOR_PIE_BACKGROUND = 0x65000000;
    private static final int COLOR_PIE_SELECT = 0xaaffffff;
    private static final int COLOR_PIE_OUTLINES = 0x55ffffff;
    private static final int COLOR_CHEVRON = 0xaaffffff;
    private static final int COLOR_BATTERY_JUICE = 0xaaffffff;
    private static final int COLOR_BATTERY_JUICE_LOW = 0xffbb33;
    private static final int COLOR_BATTERY_JUICE_CRITICAL = 0xff4444;
    private static final int COLOR_BATTERY_BACKGROUND = 0xaaffffff;
    private static final int COLOR_STATUS = 0xaaffffff;
    private static final int CHEVRON_LITE_FRAGMENTS = 30;
    private static final int INITIAL_SPEED = 1000;
    private static final int OVERALL_SPEED = 350;
    private static final int LOW_OPACITY = 40;
    private static final int NORMAL_GLOW = 150;
    private static final int HIGHLIGHTED_GLOW = 255;
    private static final int GAP_BASE = 1;
    private static final int ANGLE_BASE = 12;
    private static final float SIZE_BASE = 1.0f;

    // colors
    private int mBatteryBackgroundColor;
    private int mBatteryJuiceBackgroundColor;
    private int mBatteryJuiceCriticalBackgroundColor;
    private int mBatteryJuiceLowBackgroundColor;
    private int mChevronBackgroundColor;
    private int mMenuBackgroundColor;
    private int mOutlinesBackgroundColor;
    private int mSnapBackgroundColor;
    private int mStatusBackgroundColor;
    private int mSelectBackgroundColor;

    // structure
    private int mPanelDegree;
    private int mPanelOrientation;
    private int mInnerPieRadius;
    private int mOuterPieRadius;
    private int mPieAngle;
    private int mPieGap;
    private int mInnerChevronRadius;
    private int mOuterChevronRadius;
    private int mInnerChevronLiteRadius;
    private int mOuterChevronLiteRadius;
    private int mInnerBatteryRadius;
    private int mOuterBatteryRadius;
    private int mStatusRadius;
    private int mEmptyAngle;

    private float mPieSize;

    private Point mCenter = new Point(0, 0);
    private float mCenterDistance = 0;

    private Path mStatusPath = new Path();
    private Path mChevronPath;
    private Path[] mChevronLitePath = new Path[CHEVRON_LITE_FRAGMENTS + 2];
    private Path mBatteryPathBackground;
    private Path mBatteryPathJuice;

    // paints
    private Paint mPieBackground;
    private Paint mPieSelected;
    private Paint mPieOutlines;
    private Paint mChevronBackground;
    private Paint mBatteryJuice;
    private Paint mBatteryBackground;
    private Paint mSnapBackground;

    private Paint mClockPaint;
    private Paint mAmPmPaint;
    private Paint mStatusPaint;

    private String mClockText;
    private String mClockTextAmPm;
    private float mClockTextAmPmSize;
    private float mClockTextTotalOffset = 0;
    private float[] mClockTextOffsets = new float[20];
    private float mClockTextRotation;
    private float mClockOffset;
    private float mAmPmOffset;
    private float mStatusOffset;

    private float mStartBattery;
    private float mEndBattery;
    private int mBatteryLevel;

    private Context mContext;
    private Resources mResources;
    private PieHelper mPieHelper;
    private Vibrator mVibrator;

    private PieItem mCurrentItem;
    private List<PieItem> mItems;
    private PieControlPanel mPanel;

    private boolean mHasShown;
    private boolean mHasAssistant = false;

    private class SnapPoint {
        public boolean active;
        public int radius;
        public int gravity;
        public int x;
        public int y;

        public SnapPoint(int snapX, int snapY, int snapRadius, int snapGravity) {
            x = snapX;
            y = snapY;
            radius = snapRadius;
            gravity = snapGravity;
            active = false;
        }

        /** @return whether the gravity of this snap point is usable under the current conditions */
        public boolean isCurrentlyPossible() {
            return mPanel.isGravityPossible(gravity, false);
        }
    }

    private SnapPoint[] mSnapPoint = new SnapPoint[3];
    int mSnapRadius;
    int mSnapThickness;
    int mNumberOfSnapPoints;

    private int mImmersiveMode;
    private boolean mOpen;
    private boolean mHapticFeedback;
    private boolean mIsProtected;
    private boolean mIsAssistantAvailable;

    private int mGlowOffset = NORMAL_GLOW;

    private class CustomValueAnimator {

        public CustomValueAnimator(int animateIndex) {
            index = animateIndex;
            manual = false;
            animator = ValueAnimator.ofInt(0, 1);
            animator.addUpdateListener(new CustomAnimatorUpdateListener(index));
            fraction = 0;
        }

        public void start() {
            if (!manual) {
                animator.setDuration(duration);
                animator.start();
            }
        }

        public void cancel() {
            animator.cancel();
            fraction = 0;
        }

        public int index;
        public int duration;
        public boolean manual;
        public float fraction;
        public ValueAnimator animator;
    }

    private CustomValueAnimator[] mAnimators = new CustomValueAnimator[ANIMATOR_END + 1];

    private float mX = 0;
    private float mY = 0;

    private void getDimensions() {
        // fetch colors
        mBatteryBackgroundColor = mResources.getColor(R.color.pie_battery_background);
        mBatteryJuiceBackgroundColor = mResources.getColor(R.color.pie_battery_juice_background);
        mBatteryJuiceCriticalBackgroundColor = mResources.getColor(R.color.pie_battery_juice_critical_background);
        mBatteryJuiceLowBackgroundColor = mResources.getColor(R.color.pie_battery_juice_low_background);
        mChevronBackgroundColor = mResources.getColor(R.color.pie_chevron_background);
        mMenuBackgroundColor = mResources.getColor(R.color.pie_menu_background);
        mOutlinesBackgroundColor = mResources.getColor(R.color.pie_outlines_background);
        mSnapBackgroundColor = mResources.getColor(R.color.pie_snap_background);
        mStatusBackgroundColor = mResources.getColor(R.color.pie_status_background);
        mSelectBackgroundColor = mResources.getColor(R.color.pie_select_background);

        // fetch orientation
        mPanelDegree = mPanel.getDegree();
        mPanelOrientation = mPanel.getOrientation();

        // fetch modes
        mImmersiveMode = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.IMMERSIVE_MODE, 0);
        mHapticFeedback = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0;
        mIsProtected = mPanel.isKeyguardSecureShowing();
        mHasAssistant = mPieHelper.isAssistantAvailable();
        mIsAssistantAvailable = mPieHelper.getAssistIntent() != null;

        // hardcode for now
        mPieAngle = ANGLE_BASE;
        mPieGap = GAP_BASE;
        mPieSize = SIZE_BASE;

        // snap
        mSnapRadius = (int) (mResources.getDimensionPixelSize(R.dimen.pie_snap_radius) * mPieSize);
        mSnapThickness = (int) (mResources
                .getDimensionPixelSize(R.dimen.pie_snap_thickness) * mPieSize);

        Point outSize = new Point(0,0);
        WindowManager windowManager = (WindowManager)
                mContext.getSystemService(Context.WINDOW_SERVICE);
        windowManager.getDefaultDisplay().getRealSize(outSize);
        int mWidth = outSize.x;
        int mHeight = outSize.y;

        int snapIndex = 0;
        if (mPanelOrientation != Gravity.LEFT && mPanel.isGravityPossible(Gravity.LEFT, true)) {
            mSnapPoint[snapIndex ++] = new SnapPoint(
                    0 + mSnapThickness / 2, mHeight / 2, mSnapRadius, Gravity.LEFT);
        }

        if (mPanelOrientation != Gravity.RIGHT && mPanel.isGravityPossible(Gravity.RIGHT, true)) {
            mSnapPoint[snapIndex ++] = new SnapPoint(
                    mWidth - mSnapThickness / 2, mHeight / 2, mSnapRadius, Gravity.RIGHT);
        }

        if (mPanelOrientation != Gravity.BOTTOM && mPanel.isGravityPossible(Gravity.BOTTOM, true)) {
            mSnapPoint[snapIndex ++] = new SnapPoint(
                    mWidth / 2, mHeight - mSnapThickness / 2, mSnapRadius, Gravity.BOTTOM);
        }
        mNumberOfSnapPoints = snapIndex;

        // create pie
        mEmptyAngle = (int) (mPieAngle * mPieSize);
        mInnerPieRadius = (int) (mResources
                .getDimensionPixelSize(R.dimen.pie_radius_start) * mPieSize);
        mOuterPieRadius = (int)(mInnerPieRadius +
                mResources.getDimensionPixelSize(R.dimen.pie_radius_increment) * mPieSize);

        // calculate chevrons
        mInnerChevronRadius = (int) (mResources
                .getDimensionPixelSize(R.dimen.pie_chevron_start) * mPieSize);
        mOuterChevronRadius = (int) (mInnerChevronRadius +
                mResources.getDimensionPixelSize(R.dimen.pie_chevron_increment) * mPieSize);

        // special values for lite pie
        mInnerChevronLiteRadius = (int) (mInnerChevronRadius * 0.75);
        mOuterChevronLiteRadius = (int) (mOuterChevronRadius * 0.75);

        // create full chevron
        mChevronPath = makeSlice(mPanelDegree, mPanelDegree + 180, mInnerChevronRadius, mOuterChevronRadius, mCenter);
        // create lite chevron
        float fragmentSize = 180 / CHEVRON_LITE_FRAGMENTS;
        for (int i=0; i < CHEVRON_LITE_FRAGMENTS + 2; i++) {
            mChevronLitePath[i] = makeSlice(mPanelDegree + (i * fragmentSize), mPanelDegree + (i * fragmentSize) + fragmentSize / 2,
                    mInnerChevronLiteRadius, mOuterChevronLiteRadius, mCenter);
        }

        // calculate text circle
        mStatusRadius = (int) (mResources
                .getDimensionPixelSize(R.dimen.pie_status_start) * mPieSize);
        mStatusPath.reset();
        mStatusPath.addCircle(mCenter.x, mCenter.y, mStatusRadius, Path.Direction.CW);

        mClockPaint.setTextSize(mResources
                .getDimensionPixelSize(R.dimen.pie_clock_size) * mPieSize);
        mClockOffset = mResources
                .getDimensionPixelSize(R.dimen.pie_clock_offset) * mPieSize;
        mAmPmPaint.setTextSize(mResources
                .getDimensionPixelSize(R.dimen.pie_am_pm_size) * mPieSize);
        mAmPmOffset = mResources.getDimensionPixelSize(R.dimen.pie_am_pm_offset) * mPieSize;

        mStatusPaint.setTextSize((int) (mResources
                .getDimensionPixelSize(R.dimen.pie_status_size) * mPieSize));
        mStatusOffset = mResources.getDimensionPixelSize(R.dimen.pie_status_offset) * mPieSize;

        // battery
        mInnerBatteryRadius = (int) (mResources
                .getDimensionPixelSize(R.dimen.pie_battery_start) * mPieSize);
        mOuterBatteryRadius = (int) (mInnerBatteryRadius +
                mResources.getDimensionPixelSize(R.dimen.pie_battery_increment) * mPieSize);

        mBatteryBackground.setColor(mBatteryBackgroundColor);
        mBatteryLevel = mPieHelper.getBatteryLevel();
        if(mBatteryLevel <= PieHelper.LOW_BATTERY_LEVEL
                && mBatteryLevel > PieHelper.CRITICAL_BATTERY_LEVEL) {
            mBatteryJuice.setColor(mBatteryJuiceLowBackgroundColor);
        } else if(mBatteryLevel <= PieHelper.CRITICAL_BATTERY_LEVEL) {
            mBatteryJuice.setColor(mBatteryJuiceCriticalBackgroundColor);
        } else {
            mBatteryJuice.setColor(mBatteryJuiceBackgroundColor);
        }

        mStartBattery = mPanel.getDegree() + mEmptyAngle + mPieGap;
        mEndBattery = mPanel.getDegree() + (mPieGap <= 2 ? 88 : 90 - mPieGap);
        mBatteryPathBackground = makeSlice(mStartBattery, mEndBattery,
                mInnerBatteryRadius, mOuterBatteryRadius, mCenter);
        mBatteryPathJuice = makeSlice(mStartBattery, mStartBattery,
                mInnerBatteryRadius, mOuterBatteryRadius, mCenter);

        mSnapBackground.setColor(mSnapBackgroundColor);
        mStatusPaint.setColor(mStatusBackgroundColor);

        mPieBackground.setColor(mMenuBackgroundColor);
        mPieSelected.setColor(mSelectBackgroundColor);
        mPieOutlines.setColor(mOutlinesBackgroundColor);
        mClockPaint.setColor(mStatusBackgroundColor);
        mAmPmPaint.setColor(mStatusBackgroundColor);
        mChevronBackground.setColor(mChevronBackgroundColor);
        mBatteryJuice.setColorFilter(null);

        // measure clock
        measureClock(mPieHelper.getSimpleTime());

        // create animators
        for (int i = 0; i < mAnimators.length; i++) {
            mAnimators[i] = new CustomValueAnimator(i);
        }

        // linear animators
        mAnimators[ANIMATOR_DEC_SPEED15].duration = (int)(OVERALL_SPEED * 1.5);
        mAnimators[ANIMATOR_DEC_SPEED15].animator.setInterpolator(new DecelerateInterpolator());
        mAnimators[ANIMATOR_DEC_SPEED15].animator.setStartDelay((int)(INITIAL_SPEED * 1.5));

        mAnimators[ANIMATOR_ACC_SPEED15].duration = (int)(OVERALL_SPEED * 1.5);
        mAnimators[ANIMATOR_ACC_SPEED15].animator.setInterpolator(new AccelerateInterpolator());
        mAnimators[ANIMATOR_ACC_SPEED15].animator.setStartDelay((int)(INITIAL_SPEED * 1.5));

        // cascade accelerators
        int count = 0;
        for(int i = ANIMATOR_ACC_INC_1; i < ANIMATOR_ACC_INC_15 + 1; i++) {
            mAnimators[i].duration = 150;
            mAnimators[i].animator.setInterpolator(new DecelerateInterpolator());
            mAnimators[i].animator.setStartDelay((int)(INITIAL_SPEED * 1.5f + (++count * 75) ));
        }

        // special purpose
        mAnimators[ANIMATOR_BATTERY_METER].duration = (int)(OVERALL_SPEED * 1.5);
        mAnimators[ANIMATOR_BATTERY_METER].animator.setInterpolator(new DecelerateInterpolator());
        mAnimators[ANIMATOR_BATTERY_METER].animator.setStartDelay((int)(INITIAL_SPEED * 1.5));

        mAnimators[ANIMATOR_SNAP_GROW].manual = true;
        mAnimators[ANIMATOR_SNAP_GROW].animator.setDuration(1000);
        mAnimators[ANIMATOR_SNAP_GROW].animator.setInterpolator(new AccelerateInterpolator());
        mAnimators[ANIMATOR_SNAP_GROW].animator.addListener(new Animator.AnimatorListener() {
            @Override public void onAnimationCancel(Animator animation) {}
            @Override public void onAnimationRepeat(Animator animation) {}
            @Override public void onAnimationStart(Animator animation) {}
            @Override public void onAnimationEnd(Animator animation) {
                if (mAnimators[ANIMATOR_SNAP_GROW].fraction == 1) {
                    for (int i = 0; i < 2; i++) {
                        SnapPoint snap = mSnapPoint[i];
                        if (snap != null && snap.active && snap.isCurrentlyPossible()) {
                            if(mHapticFeedback) mVibrator.vibrate(2);
                            deselect();
                            animateOut();
                            mPanel.reorient(snap.gravity);
                        }
                    }
                }
            }});
    }

    // may regulate a bit offsets, ":" looks to closer to previous and next number
    private void measureClock(String text) {
        mClockText = text;

        mClockTextAmPm = mPieHelper.getAmPm();
        mClockTextAmPmSize = mAmPmPaint.measureText(mClockTextAmPm);
        mClockTextTotalOffset = 0;

        for(int i = 0; i < mClockText.length(); i++) {
            char character = mClockText.charAt(i);
            float measure = mClockPaint.measureText("" + character);
            mClockTextOffsets[i] = measure * (character == '1' || character == ':' ? 0.8f : 1f);
            mClockTextTotalOffset += measure *
                    (character == '1' || character == ':' ? 0.8f : 1f);
        }

        mClockTextTotalOffset += mClockTextAmPmSize;
        mClockTextRotation = mPanel.getDegree()
                + (180 - (mClockTextTotalOffset * 360 / (2f * (mStatusRadius
                + Math.abs(mClockOffset)) * (float) Math.PI)))
                - (mPieHelper.is24Hours() ? 8 : 2);
    }

    /**
     * Creates a new pie outline view
     * @Param context the current context
     * @Param panel the current PieControlPanel
     */
    public PieMenu(Context context, PieControlPanel panel) {
        super(context);

        mContext = context;
        mResources = mContext.getResources();
        mPanel = panel;

        setWillNotDraw(false);
        setDrawingCacheEnabled(false);

        mVibrator = (Vibrator) mContext.getSystemService(Context.VIBRATOR_SERVICE);
        mPieHelper = PieHelper.getInstance();

        // initialize main paints
        mPieBackground = new Paint();
        mPieSelected = new Paint();
        mPieOutlines = new Paint();
        mChevronBackground = new Paint();
        mBatteryJuice = new Paint();
        mBatteryBackground = new Paint();
        mSnapBackground = new Paint();

        // initialize classes
        mItems = new ArrayList<PieItem>();
        mPieBackground.setAntiAlias(true);
        mPieSelected.setAntiAlias(true);
        mPieOutlines.setAntiAlias(true);
        mPieOutlines.setStyle(Style.STROKE);
        mPieOutlines.setStrokeWidth(mResources.getDimensionPixelSize(R.dimen.pie_outline));
        mChevronBackground.setAntiAlias(true);
        mBatteryJuice.setAntiAlias(true);
        mBatteryBackground.setAntiAlias(true);
        mSnapBackground.setAntiAlias(true);

        mClockPaint = new Paint();
        mClockPaint.setAntiAlias(true);
        mClockPaint.setTypeface(Typeface.create(FONT_FAMILY_LIGHT, Typeface.NORMAL));

        mAmPmPaint = new Paint();
        mAmPmPaint.setAntiAlias(true);
        mAmPmPaint.setTypeface(Typeface.create(FONT_FAMILY_CONDENSED, Typeface.NORMAL));

        mStatusPaint = new Paint();
        mStatusPaint.setAntiAlias(true);
        mStatusPaint.setTypeface(Typeface.create(FONT_FAMILY_CONDENSED, Typeface.NORMAL));

        // Clock observer
        mPieHelper.setOnClockChangedListener(new PieHelper.OnClockChangedListener() {
            public void onChange(String s) {
                measureClock(s);
            }
        });

        // Get all dimensions
        getDimensions();
    }

    public void addItem(PieItem item) {
        mItems.add(item);
    }

    public void show(boolean show) {
        mOpen = show;
        if (mOpen) {
            getDimensions();

            // de-select all items
            mCurrentItem = null;
            for (PieItem item : mItems) {
                item.setSelected(false);
            }

            // calculate pie
            layoutPie();
        }
        invalidate();
    }

    public void setCenter(int x, int y) {
        mCenter.y = y;
        mCenter.x = x;

        mStatusPath.reset();
        mStatusPath.addCircle(mCenter.x, mCenter.y, mStatusRadius, Path.Direction.CW);
    }

    private boolean canItemDisplay(PieItem item) {
        return !(item.getName().equals(PieController.MENU_BUTTON) && !mPanel.currentAppUsesMenu());
    }

    private void layoutPie() {
        float emptyangle = mEmptyAngle * (float)Math.PI / 180;
        int inner = mInnerPieRadius;
        int outer = mOuterPieRadius;

        int itemCount = mItems.size();
        if (!mPanel.currentAppUsesMenu()) itemCount--;

        int lesserSweepCount = 0;
        for (PieItem item : mItems) {
            boolean canDisplay = canItemDisplay(item);
            if (canDisplay) {
                if (item.isLesser()) {
                    lesserSweepCount += 1;
                }
            }
        }

        float adjustedSweep = lesserSweepCount > 0 ?
                (((1 - 0.65f) * lesserSweepCount) / (itemCount-lesserSweepCount)) : 0;
        float sweep = 0;
        float angle = 0;
        float total = 0;

        int count = 0;
        for (PieItem item : mItems) {
            if (!canItemDisplay(item)) continue;

            sweep = ((float) (Math.PI - 2 * emptyangle) /
                    itemCount) * (item.isLesser() ? 0.65f : 1 + adjustedSweep);
            angle = (emptyangle + sweep / 2 - (float) Math.PI/2);
            item.setPath(
                    makeSlice(getDegrees(0) - mPieGap, getDegrees(sweep) + mPieGap,
                            outer, inner, mCenter, (mPieGap > 0 ? mPieGap + 0.4f : 0), count != 0));
            View view = item.getView();

            if (view != null) {
                view.measure(view.getLayoutParams().width, view.getLayoutParams().height);
                int w = view.getMeasuredWidth();
                int h = view.getMeasuredHeight();
                int r = inner + (outer - inner) * 2 / 3;
                int x = (int) (r * Math.sin(total + angle));
                int y = (int) (r * Math.cos(total + angle));

                switch(mPanelOrientation) {
                    case Gravity.LEFT:
                        y = mCenter.y - (int) (r * Math.sin(total + angle)) - h / 2;
                        x = (int) (r * Math.cos(total + angle)) - w / 2;
                        break;
                    case Gravity.RIGHT:
                        y = mCenter.y - (int) (Math.PI / 2 - r * Math.sin(total + angle)) - h / 2;
                        x = mCenter.x - (int) (r * Math.cos(total + angle)) - w / 2;
                        break;
                    case Gravity.BOTTOM:
                        y = mCenter.y - y - h / 2;
                        x = mCenter.x - x - w / 2;
                        break;
                }
                view.layout(x, y, x + w, y + h);
            }
            float itemstart = total + angle - sweep / 2;
            item.setGeometry(itemstart, sweep, inner, outer);
            total += sweep;
            count++;
        }
    }

    // param angle from 0 - pi to android degrees (clockwise starting at 3)
    private float getDegrees(double angle) {
        return (float) (270 - 180 * angle / Math.PI);
    }

    private class CustomAnimatorUpdateListener implements ValueAnimator.AnimatorUpdateListener {

        private int mIndex;
        CustomAnimatorUpdateListener(int index) {
            mIndex = index;
        }

        @Override
        public void onAnimationUpdate(ValueAnimator animation) {
            float fraction = animation.getAnimatedFraction();
            mAnimators[mIndex].fraction = fraction;
            if(fraction != 1f) mHasShown = true;

            // special purpose animators go here
            if (mIndex == ANIMATOR_BATTERY_METER) {
                mBatteryPathJuice = makeSlice(mStartBattery,
                        mStartBattery + (float)animation.getAnimatedFraction() *
                                (mBatteryLevel * (mEndBattery - mStartBattery) / 100),
                                        mInnerBatteryRadius, mOuterBatteryRadius, mCenter);
            }
            invalidate();
        }
    }

    private void cancelAnimation() {
        for (int i = 0; i < mAnimators.length; i++) {
            mAnimators[i].cancel();
            invalidate();
        }
    }

    private void animateIn() {
        // cancel & start all animations
        cancelAnimation();
        for (int i = 0; i < mAnimators.length; i++) {
            mAnimators[i].start();
        }
    }

    public void animateOut() {
        mHasShown = false;
        mPanel.show(false);
        cancelAnimation();
    }

    public void animateInImmediate() {
        mHasShown = true;
        cancelAnimation();
        for (int i = 0; i < mAnimators.length; i++) {
            mAnimators[i].animator.setStartDelay(0);
            mAnimators[i].start();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mOpen) {
            int state;

            // draw background
            canvas.drawARGB((int)(mAnimators[ANIMATOR_DEC_SPEED15].fraction * 0xcc), 0, 0, 0);

            // snap points
            final int threshold = mImmersiveMode == IMMERSIVE_MODE_FULL && !mIsProtected ?
                    mOuterChevronRadius : mOuterChevronLiteRadius;

            if (mCenterDistance > threshold) {
                for (int i = 0; i < mNumberOfSnapPoints; i++) {
                    SnapPoint snap = mSnapPoint[i];
                    if (!snap.isCurrentlyPossible()) continue;

                    if (snap == null) continue;

                    float snapDistanceX = snap.x - mX;
                    float snapDistanceY = snap.y - mY;
                    float fraction = 1f
                            + (snap.active ? mAnimators[ANIMATOR_SNAP_GROW].fraction * 2f : 0f);
                    float snapDistance = (float) Math.sqrt(
                            Math.pow(snapDistanceX, 2) + Math.pow(snapDistanceY, 2));
                    int radius = (int) (snap.radius * fraction * 0.7f);

                    float snapTouch = snapDistance < mSnapRadius * 7 ? 200 -
                            (snapDistance * (200 - LOW_OPACITY) / (mSnapRadius * 7)) : LOW_OPACITY;

                    mSnapBackground.setAlpha((int) snapTouch);

                    Path circle = new Path();
                    boolean onX = snap.gravity == Gravity.LEFT || snap.gravity == Gravity.RIGHT;
                    int displacement = ((radius / 2) * (snap.gravity == Gravity.LEFT ? 1 : -1));
                    circle.addCircle(snap.x - (onX ? displacement : 0),
                            snap.y - (onX ? 0 : displacement), radius, Path.Direction.CW);
                    canvas.drawPath(circle, mSnapBackground);
                }
            }

            // draw base menu
            for (PieItem item : mItems) {
                if (!canItemDisplay(item)) continue;
                drawItem(canvas, item);
            }

            // draw chevron ring
            mChevronBackground.setAlpha((int)
                (mAnimators[ANIMATOR_DEC_SPEED15].fraction * mGlowOffset));

            state = canvas.save();
            canvas.rotate(90, mCenter.x, mCenter.y);
            if (mImmersiveMode == IMMERSIVE_MODE_FULL && !mIsProtected) {
                canvas.drawPath(mChevronPath, mChevronBackground);
            } else if (mImmersiveMode == IMMERSIVE_MODE_HIDE_NAVBAR_ONLY) {
                for (int i=0; i < CHEVRON_LITE_FRAGMENTS + 2; i++) {
                    canvas.drawPath(mChevronLitePath[i], mChevronBackground);
                }
            }
            canvas.restoreToCount(state);

            // paint status report only if settings allow
            if (mImmersiveMode == IMMERSIVE_MODE_FULL && !mIsProtected) {
                // draw battery
                mBatteryBackground.setAlpha((int)
                     (mAnimators[ANIMATOR_DEC_SPEED15].fraction * 0x22));
                mBatteryJuice.setAlpha((int)
                        (mAnimators[ANIMATOR_ACC_SPEED15].fraction * 0x88));

                state = canvas.save();
                canvas.rotate(90, mCenter.x, mCenter.y);
                canvas.drawPath(mBatteryPathBackground, mBatteryBackground);
                canvas.restoreToCount(state);

                state = canvas.save();
                canvas.rotate(90, mCenter.x, mCenter.y);
                canvas.drawPath(mBatteryPathJuice, mBatteryJuice);
                canvas.restoreToCount(state);

                // draw clock && AM/PM
                state = canvas.save();
                canvas.rotate(mClockTextRotation -
                        (1 - mAnimators[ANIMATOR_DEC_SPEED15].fraction) * 90, mCenter.x, mCenter.y);

                mClockPaint.setAlpha((int)(mAnimators[ANIMATOR_DEC_SPEED15].fraction * 0xcc));
                float lastPos = 0;
                for(int i = 0; i < mClockText.length(); i++) {
                    canvas.drawTextOnPath("" + mClockText.charAt(i),
                            mStatusPath, lastPos - mClockTextAmPmSize, mClockOffset, mClockPaint);
                    lastPos += mClockTextOffsets[i];
                }

                mAmPmPaint.setAlpha((int)(mAnimators[ANIMATOR_DEC_SPEED15].fraction * 0xaa));
                canvas.drawTextOnPath(mClockTextAmPm, mStatusPath,
                        lastPos - mClockTextAmPmSize, mAmPmOffset, mAmPmPaint);
                canvas.restoreToCount(state);

                // device status information and date
                mStatusPaint.setAlpha((int)(mAnimators[ANIMATOR_ACC_SPEED15].fraction * 0xaa));

                state = canvas.save();
                final boolean isTelephony = mPieHelper.supportsTelephony();
                canvas.rotate(mPanel.getDegree() + 180
                        + (1 - mAnimators[ANIMATOR_DEC_SPEED15].fraction) * 90, mCenter.x, mCenter.y);
                if (isTelephony) {
                    canvas.drawTextOnPath(mPieHelper.getNetworkProvider() + " " + mPieHelper.getNetworkType(),
                            mStatusPath, 0, mStatusOffset * 4, mStatusPaint);
                }
                canvas.drawTextOnPath(mPieHelper.getCount() + " "
                        + mContext.getString(R.string.status_bar_latest_events_title).toUpperCase(),
                                mStatusPath, 0, mStatusOffset * 3, mStatusPaint);
                canvas.drawTextOnPath(mPieHelper.getSimpleDate(),
                        mStatusPath, 0, mStatusOffset * 2, mStatusPaint);
                canvas.drawTextOnPath(
                        mContext.getString(R.string.quick_settings_wifi_label).toUpperCase()
                                + ": " + mPieHelper.getWifiSsid(), mStatusPath,
                                        0, mStatusOffset * 1, mStatusPaint);
                canvas.drawTextOnPath(mPieHelper.getBatteryLevelReadable(),
                        mStatusPath, 0, mStatusOffset * 0, mStatusPaint);
                canvas.restoreToCount(state);

                state = canvas.save();
                canvas.rotate(mPanel.getDegree() + 180, mCenter.x, mCenter.y);

                canvas.restoreToCount(state);
            }
        }
    }

    private void drawItem(Canvas canvas, PieItem item) {
        if (item.getView() != null) {
            int state = canvas.save();
            canvas.rotate(getDegrees(item.getStartAngle())
                        + mPanel.getDegree(), mCenter.x, mCenter.y);
            canvas.drawPath(item.getPath(), item.isSelected() ? mPieSelected : mPieBackground);
            canvas.drawPath(item.getPath(), mPieOutlines);
            canvas.restoreToCount(state);

            state = canvas.save();
            ImageView view = (ImageView)item.getView();
            canvas.translate(view.getX(), view.getY());
            canvas.rotate(getDegrees(item.getStartAngle()
                    + item.getSweep() / 2) + mPanel.getDegree(),
                            view.getWidth() / 2, view.getHeight() / 2);

            view.draw(canvas);
            canvas.restoreToCount(state);
        }
    }

    private Path makeSlice(float start, float end, int outer, int inner, Point center) {
        return makeSlice(start, end, outer, inner, center, 0, true);
    }

    private Path makeSlice(float start, float end, int outer,
                int inner, Point center, float narrow, boolean bothEnds) {
        RectF bb = new RectF(center.x - outer, center.y - outer,
                center.x + outer, center.y + outer);
        RectF bbi = new RectF(center.x - inner, center.y - inner,
                center.x + inner, center.y + inner);
        Path path = new Path();
        path.arcTo(bb, start, end - start, true);
        path.arcTo(bbi, end + narrow, start - end - (bothEnds ? narrow : narrow * 2));
        path.close();
        return path;
    }

    // touch handling for pie
    @Override
    public boolean onTouchEvent(MotionEvent evt) {
        if (evt.getPointerCount() > 1) return true;
        mX = evt.getRawX();
        mY = evt.getRawY();
        float distanceX = mCenter.x - mX;
        float distanceY = mCenter.y - mY;
        mCenterDistance = (float) Math.sqrt(Math.pow(distanceX, 2) + Math.pow(distanceY, 2));
        float shadeTreshold =
                mImmersiveMode == IMMERSIVE_MODE_FULL ?
                        mOuterChevronRadius : mOuterChevronLiteRadius;

        int action = evt.getActionMasked();
        if (MotionEvent.ACTION_DOWN == action) {
            // open panel
            animateIn();
        } else if (MotionEvent.ACTION_MOVE == action) {
            for (int i = 0; i < mNumberOfSnapPoints; i++) {
                SnapPoint snap = mSnapPoint[i];
                if (!snap.isCurrentlyPossible()) continue;

                if (snap == null) continue;

                float snapDistanceX = snap.x - mX;
                float snapDistanceY = snap.y - mY;
                float snapDistance = (float)
                        Math.sqrt(Math.pow(snapDistanceX, 2) + Math.pow(snapDistanceY, 2));

                if (snapDistance < mSnapRadius) {
                    if (!snap.active) {
                        mAnimators[ANIMATOR_SNAP_GROW].cancel();
                        mAnimators[ANIMATOR_SNAP_GROW].animator.start();
                        if(mHapticFeedback) mVibrator.vibrate(2);
                    }
                    snap.active = true;
                    mGlowOffset = NORMAL_GLOW;
                } else {
                    if (snap.active) {
                        mAnimators[ANIMATOR_SNAP_GROW].cancel();
                    }
                    snap.active = false;
                }
            }

            // trigger the shades
            if (mCenterDistance > shadeTreshold) {
                mGlowOffset = HIGHLIGHTED_GLOW;
                if(!mHasShown) animateInImmediate();
                deselect();
            }

            // take back shade trigger if user decides to abandon his gesture
            if (mCenterDistance < shadeTreshold) {
                mGlowOffset = NORMAL_GLOW;
                // check for onEnter separately or'll face constant deselect
                PieItem item = findItem(getPolar(mX, mY));
                if (item != null) {
                    if (mCenterDistance < shadeTreshold &&
                            mCenterDistance > (mInnerPieRadius * 0.75f)) {
                        onEnter(item);
                    } else {
                        deselect();
                    }
                }
            }
            invalidate();
        } else if (MotionEvent.ACTION_UP == action) {
            if (mOpen) {
                PieItem item = mCurrentItem;

                // check for click actions
                if (item != null && item.getView() != null && mCenterDistance < shadeTreshold) {
                    if(mHapticFeedback) mVibrator.vibrate(2);
                    item.getView().performClick();
                    if (item.getName().equals(RECENT_BUTTON)) {
                        try {
                            ActivityManagerNative.getDefault().dismissKeyguardOnNextActivity();
                        } catch (ActivityNotFoundException e) {
                            // Something happened
                        } catch (RemoteException ex) {
                            // system is dead
                        }
                    }
                }

                // check for google now action
                if (mCenterDistance > shadeTreshold) {
                    if (mIsAssistantAvailable) mPieHelper.launchAssistAction();
                }
            }

            // say good bye
            deselect();
            animateOut();
            return true;
        }
        // always re-dispatch event
        return false;
    }

    private void onEnter(PieItem item) {
        if (mCurrentItem == item) return;

        // deselect
        if (mCurrentItem != null) {
            mCurrentItem.setSelected(false);
        }
        if (item != null) {
            // clear up stack
            playSoundEffect(SoundEffectConstants.CLICK);
            item.setSelected(true);
            mCurrentItem = item;
        } else {
            mCurrentItem = null;
        }
    }

    private void deselect() {
        if (mCurrentItem != null) {
            mCurrentItem.setSelected(false);
        }
        mCurrentItem = null;
    }

    private float getPolar(float x, float y) {
        float deltaY = mCenter.y - y;
        float deltaX = mCenter.x - x;
        float adjustAngle = 0;;
        switch(mPanelOrientation) {
            case Gravity.LEFT:
                adjustAngle = 90;
                break;
            case Gravity.RIGHT:
                adjustAngle = -90;
                break;
        }
        return (adjustAngle + (float) Math.atan2(deltaX,
                deltaY) * 180 / (float) Math.PI) * (float) Math.PI / 180;
    }

    private PieItem findItem(float polar) {
        if (mItems != null) {
            for (PieItem item : mItems) {
                if (!canItemDisplay(item)) continue;
                if (inside(polar, item)) {
                    return item;
                }
            }
        }
        return null;
    }

    private boolean inside(float polar, PieItem item) {
        return (item.getStartAngle() < polar)
        && (item.getStartAngle() + item.getSweep() > polar);
    }
}
