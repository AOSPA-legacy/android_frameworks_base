/*
 * Copyright (C) 2014, ParanoidAndroid Project
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

import android.app.KeyguardManager;
import android.app.StatusBarManager;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.gesture.EdgeGestureManager;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnTouchListener;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.View.OnClickListener;
import android.view.ViewGroup.LayoutParams;
import android.widget.ImageView;
import android.widget.ImageView.ScaleType;

import com.android.internal.util.gesture.EdgeGesturePosition;
import com.android.internal.util.gesture.EdgeServiceConstants;
import com.android.systemui.R;
import com.android.systemui.recent.RecentsActivity;
import com.android.systemui.recent.NavigationCallback;
import com.android.systemui.statusbar.BaseStatusBar;

import static com.android.systemui.statusbar.phone.QuickSettingsModel.IMMERSIVE_MODE_OFF;
import static com.android.systemui.statusbar.phone.QuickSettingsModel.IMMERSIVE_MODE_FULL;
import static com.android.systemui.statusbar.phone.QuickSettingsModel.IMMERSIVE_MODE_HIDE_ONLY_NAVBAR;
import static com.android.systemui.statusbar.phone.QuickSettingsModel.IMMERSIVE_MODE_HIDE_ONLY_STATUSBAR;

/**
 * Pie Controller
 * Sets and controls the pie menu and associated pie items
 * Singleton must be initialized.
 */
public class PieController extends EdgeGestureManager.EdgeGestureActivationListener implements OnTouchListener, OnClickListener, NavigationCallback {

    public static final String BACK_BUTTON = "##back##";
    public static final String HOME_BUTTON = "##home##";
    public static final String MENU_BUTTON = "##menu##";
    public static final String RECENT_BUTTON = "##recent##";
    public static final String CLEAR_ALL_BUTTON = "##clear##";

    private static PieController mInstance;
    private static PieHelper mPieHelper;

    private BaseStatusBar mBar;
    private Context mContext;
    private Handler mHandler;

    private KeyguardManager mKeyguardManager;
    private WindowManager mWindowManager;
    private EdgeGestureManager mPieManager;
    private Point mEdgeGestureTouchPos = new Point(0, 0);;
    private int mPiePosition;

    private PieControlPanel mPanel;
    private PieControlPanel mPieControlPanel;

    private PieItem mBack;
    private PieItem mHome;
    private PieItem mMenu;
    private PieItem mRecent;

    protected PieMenu mPie;
    protected int mItemSize;

    private int mNavigationIconHints;
    private boolean mPieAttached;
    private boolean mForcePieCentered;

    private PieController() {
        super(Looper.getMainLooper());
    }

    /**
     * Creates a new instance of pie
     * @Param context the current Context
     * @Param wm the current Window Manager
     * @Param bar the current BaseStusBar
     */
    public void init(Context context, WindowManager wm, BaseStatusBar bar) {
        mContext = context;
        mHandler = new Handler();
        mWindowManager = wm;
        mBar = bar;
        mPieHelper = PieHelper.getInstance();
        mPieHelper.init(mContext, mBar);
        mItemSize = (int) context.getResources().getDimension(R.dimen.pie_item_size);
        mKeyguardManager = (KeyguardManager) mContext.getSystemService(Context.KEYGUARD_SERVICE);
        RecentsActivity.addNavigationCallback(this);

        mPieManager = EdgeGestureManager.getInstance();
        mPieManager.setEdgeGestureActivationListener(this);
        mForcePieCentered = mContext.getResources().getBoolean(R.bool.config_forcePieCentered);
    }

    public static PieController getInstance() {
        if (mInstance == null) mInstance = new PieController();
        return mInstance;
    }

    public void detachPie() {
        if(mPieControlPanel != null) {
            mBar.updatePieControls(!mPieAttached);
        }
    }

    public void resetPie(boolean enabled, int gravity) {
        if(mPieAttached) {
            if (mPieControlPanel != null) mWindowManager.removeView(mPieControlPanel);
            mPieAttached = false;
        }
        if(enabled) attachPie(gravity);
    }

    private boolean showPie() {
        final boolean pieEnabled = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.PIE_STATE, 0, UserHandle.USER_CURRENT) == 1;
        final int immersiveMode = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.IMMERSIVE_MODE, 0, UserHandle.USER_CURRENT);
        return pieEnabled && immersiveMode != IMMERSIVE_MODE_OFF
                && immersiveMode != IMMERSIVE_MODE_HIDE_ONLY_STATUSBAR;
    }

    public void attachPie(int gravity) {
        if(showPie()) {
            // want some slice?
            switch(gravity) {
                // this is just main gravity, the trigger is centered later
                case 1:
                    addPieInLocation(Gravity.LEFT);
                    break;
                case 2:
                    addPieInLocation(Gravity.RIGHT);
                    break;
                default:
                    addPieInLocation(Gravity.BOTTOM);
                    break;
            }
        }
    }

    private void addPieInLocation(int gravity) {
        if(mPieAttached) return;

        // pie panel
        mPieControlPanel = (PieControlPanel) View.inflate(mContext,
                R.layout.pie_control_panel, null);

        // init panel
        mPieControlPanel.init(mHandler, mBar, gravity);
        mPieControlPanel.setOnTouchListener(this);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_STATUS_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT);
        lp.setTitle("PieControlPanel");
        lp.windowAnimations = android.R.style.Animation;

        // pie edge gesture
        mPiePosition = mPieControlPanel.getOrientation();
        setupEdgeGesture(mPiePosition);

        mWindowManager.addView(mPieControlPanel, lp);
        mPieAttached = true;
    }

    private boolean activateFromListener(int touchX, int touchY, EdgeGesturePosition position) {
        if (!mPieControlPanel.isShowing()) {
            mEdgeGestureTouchPos.x = touchX;
            mEdgeGestureTouchPos.y = touchY;
            mPieControlPanel.show();
            return true;
        }
        return false;
    }

    private void setupEdgeGesture(int gravity) {
        int triggerSlot = convertToEdgeGesturePosition(gravity);

        int sensitivity = mContext.getResources().getInteger(R.integer.pie_gesture_sensivity);
        if (sensitivity < EdgeServiceConstants.SENSITIVITY_LOWEST
                || sensitivity > EdgeServiceConstants.SENSITIVITY_HIGHEST) {
            sensitivity = EdgeServiceConstants.SENSITIVITY_DEFAULT;
        }

        mPieManager.updateEdgeGestureActivationListener(this,
                sensitivity << EdgeServiceConstants.SENSITIVITY_SHIFT
                    | triggerSlot
                    | EdgeServiceConstants.LONG_LIVING
                    | EdgeServiceConstants.UNRESTRICTED);
    }

    private int convertToEdgeGesturePosition(int gravity) {
        switch (gravity) {
            case Gravity.LEFT:
                return EdgeGesturePosition.LEFT.FLAG;
            case Gravity.RIGHT:
                return EdgeGesturePosition.RIGHT.FLAG;
            case Gravity.BOTTOM:
            default: // fall back
                return EdgeGesturePosition.BOTTOM.FLAG;
        }
    }

    public PieControlPanel getControlPanel() {
        return mPieControlPanel;
    }

    public void populateMenu() {
        mBack = makeItem(R.drawable.ic_sysbar_back, 1, BACK_BUTTON, false);
        mHome = makeItem(R.drawable.ic_sysbar_home, 1, HOME_BUTTON, false);
        mRecent = makeItem(R.drawable.ic_sysbar_recent, 1, RECENT_BUTTON, false);
        mMenu = makeItem(R.drawable.ic_sysbar_menu, 1, MENU_BUTTON, true);

        mPie.addItem(mMenu);
        mPie.addItem(mRecent);
        mPie.addItem(mHome);
        mPie.addItem(mBack);
    }

    @Override
    public void setNavigationIconHints(int button, int hints, boolean force) {
        if (mRecent == null || mBack == null) return;
        mNavigationIconHints = hints;

        if (button == NavigationCallback.NAVBAR_RECENTS_HINT) {
            boolean alt = (0 != (hints &
                    StatusBarManager.NAVIGATION_HINT_RECENT_ALT)
                            && !mKeyguardManager.isKeyguardLocked()
                            && Settings.System.getIntForUser(mContext.getContentResolver(),
                                Settings.System.NAVBAR_RECENTS_CLEAR_ALL, 0,
                                UserHandle.USER_CURRENT) != 2);
            mRecent.setIcon(alt ? R.drawable.ic_sysbar_recent_clear
                    : R.drawable.ic_sysbar_recent);
            mRecent.setName(alt ? CLEAR_ALL_BUTTON : RECENT_BUTTON);
        } else if (button == NavigationCallback.NAVBAR_BACK_HINT) {
            boolean alt = (0 != (hints &
                    StatusBarManager.NAVIGATION_HINT_BACK_ALT));
            mBack.setIcon(alt ? R.drawable.ic_sysbar_back_ime
                    : R.drawable.ic_sysbar_back);
        }
    }

    @Override
    public int getNavigationIconHints() {
        return mNavigationIconHints;
    }

    @Override
    public void onClick(View v) {
        if (mPanel != null) {
            mPanel.onNavButtonPressed((String) v.getTag());
        }
    }

    protected PieItem makeItem(int image, int l, String name, boolean lesser) {
        ImageView view = new ImageView(mContext);
        view.setImageResource(image);
        view.setMinimumWidth(mItemSize);
        view.setMinimumHeight(mItemSize);
        view.setScaleType(ScaleType.CENTER);
        LayoutParams lp = new LayoutParams(mItemSize, mItemSize);
        view.setLayoutParams(lp);
        view.setOnClickListener(this);
        return new PieItem(view, mContext, l, name, lesser);
    }

    public void show(boolean show) {
        mPie.show(show);
    }

    public void setCenter(int x, int y) {
        if (!mForcePieCentered) {
            switch (mPiePosition) {
                case Gravity.LEFT:
                case Gravity.RIGHT:
                    y = mEdgeGestureTouchPos.y;
                    break;
                case Gravity.BOTTOM:
                    x = mEdgeGestureTouchPos.x;
                    break;
            }
        }
        mPie.setCenter(x, y);
    }

    public void setControlPanel(PieControlPanel panel) {
        mPanel = panel;
        mPie = new PieMenu(mContext, mPanel);
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT);
        mPie.setLayoutParams(lp);
        populateMenu();
        mPanel.addView(mPie);
    }

    public interface OnNavButtonPressedListener {
        public void onNavButtonPressed(String buttonName);
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        mPie.onTouchEvent(event);
        return true;
    }

    @Override
    public void onEdgeGestureActivation(int touchX, int touchY, EdgeGesturePosition position,
            int flags) {
        if (mPieControlPanel != null &&
                activateFromListener(touchX, touchY, position)) {
            // give the main thread some time to do the bookkeeping
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (!gainTouchFocus(mPieControlPanel.getWindowToken())) {
                        detachPie();
                    }
                    restoreListenerState();
                }
            });
        } else {
            restoreListenerState();
        }
    }
}
