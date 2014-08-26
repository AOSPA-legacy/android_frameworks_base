/*
 * Copyright (C) 2014 ParanoidAndroid Project
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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Point;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.WindowManager;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

public class BarBackgroundUpdater {
    private final static boolean DEBUG = false;
    private final static String LOG_TAG = BarBackgroundUpdater.class.getSimpleName();

    private final static int DELAY_IN_MILLIS = 1000 / 10;
    private static boolean PAUSED = false;

    private final static BroadcastReceiver RECEIVER = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            synchronized(BarBackgroundUpdater.class) {
                if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
                    PAUSED = true;
                } else if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
                    PAUSED = false;
                }
            }
        }

    };

    private final static Thread THREAD = new Thread(new Runnable() {

        @Override
        public void run() {
            while (true) {
                while (PAUSED) {
                    // we have been told to do nothing; stall for a bit

                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        return;
                    }
                }

                if (DELAY_IN_MILLIS <= 0) {
                    // we have been told to delay for a weird timespan; retry in a bit

                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        return;
                    }

                    continue;
                }

                final Context context = mContext;

                if (context == null) {
                    // we haven't been initiated yet; retry in a bit

                    try {
                        Thread.sleep(DELAY_IN_MILLIS);
                    } catch (InterruptedException e) {
                        return;
                    }

                    continue;
                }

                if (mStatusEnabled || mNavigationEnabled) {
                    final WindowManager wm =
                        (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);

                    final Point p = new Point();
                    wm.getDefaultDisplay().getSize(p);

                    final int rotation = wm.getDefaultDisplay().getRotation();
                    final boolean isLandscape = rotation == Surface.ROTATION_90 ||
                        rotation == Surface.ROTATION_270;

                    final Resources r = context.getResources();
                    final int statusBarHeight = r.getDimensionPixelSize(
                        r.getIdentifier("status_bar_height", "dimen", "android"));
                    final int navigationBarHeight = r.getDimensionPixelSize(
                        r.getIdentifier("navigation_bar_height" + (isLandscape ?
                            "_landscape" : ""), "dimen", "android"));

                    if (navigationBarHeight <= 0 && mNavigationEnabled) {
                        // the navigation bar height is not positive - no dynamic navigation bar
                        Settings.System.putInt(context.getContentResolver(),
                            Settings.System.DYNAMIC_NAVIGATION_BAR_STATE, 0);

                        // just drop this check and retry in a bit as configuration has changed
                        try {
                            Thread.sleep(DELAY_IN_MILLIS);
                        } catch (InterruptedException e) {
                            return;
                        }

                        continue;
                    }

                    final int xForStatusBar = -(2 + (isLandscape ? navigationBarHeight : 0));
                    final int yBelowStatusBar = statusBarHeight + 2;
                    final int yAboveNavigationBar = -(navigationBarHeight + 2);

                    // we have to manually handle landscape since it is not handled by the API
                    final int width = isLandscape ? p.y : p.x;
                    final int height = isLandscape ? p.x : p.y;

                    final Matrix matrix = new Matrix();
                    switch (rotation) {
                    case Surface.ROTATION_0:
                        matrix.reset();
                        break;
                    case Surface.ROTATION_90:
                        matrix.setRotate(270, 0, 0);
                        matrix.postTranslate(0, width);
                        break;
                    case Surface.ROTATION_180:
                        matrix.setRotate(180, 0, 0);
                        matrix.postTranslate(width, height);
                        break;
                    case Surface.ROTATION_270:
                        matrix.setRotate(90, 0, 0);
                        matrix.postTranslate(height, 0);
                        break;
                    }

                    final Bitmap rawScreen = SurfaceControl.screenshot(width, height);
                    if (rawScreen == null) {
                        // something went wrong during the screenshot grabbing; retry in a bit

                        try {
                            Thread.sleep(DELAY_IN_MILLIS);
                        } catch (InterruptedException e) {
                            return;
                        }

                        continue;
                    }

                    final Bitmap screenshot = Bitmap.createBitmap(width, height,
                        rawScreen.getConfig());

                    final Canvas canvas = new Canvas(screenshot);
                    canvas.drawColor(0xFF000000);
                    canvas.drawBitmap(rawScreen, matrix, null);
                    canvas.setBitmap(null);
                    rawScreen.recycle();

                    final int sbColorOne = getPixel(screenshot, 1, 1);
                    final int sbColorTwo = getPixel(screenshot, 1, 5);

                    final int nbColorOne = getPixel(screenshot, -1, -1);
                    final int nbColorTwo = getPixel(screenshot, -1, -5);

                    final int topColorLeft = getPixel(screenshot, 1, yBelowStatusBar);
                    final int topColorRight = getPixel(screenshot, xForStatusBar,
                        yBelowStatusBar);
                    final int topColorCenter = getPixel(screenshot, (int) (xForStatusBar / 2),
                        yBelowStatusBar);

                    final int botColorLeft = getPixel(screenshot, 1, yAboveNavigationBar);
                    final int botColorRight = getPixel(screenshot, xForStatusBar,
                        yAboveNavigationBar);
                    final int botColorCenter = getPixel(screenshot, (int) (xForStatusBar / 2),
                        yAboveNavigationBar);

                    screenshot.recycle(); // no more colors are needed - clean up

                    final boolean isStatusBarConsistent = sbColorOne == sbColorTwo;
                    final boolean isNavigationBarConsistent = nbColorOne == nbColorTwo;

                    if (mStatusEnabled) {
                        final int tmp;

                        if (topColorLeft == topColorRight) {
                            // status bar appears to be completely uniform
                            tmp = topColorLeft;
                        } else if (topColorLeft == topColorCenter ||
                                topColorRight == topColorCenter) {
                            // a side of the status bar appears to be uniform
                            tmp = topColorCenter;
                        } else {
                            // status bar does not appear to be uniform at all
                            tmp = sampleColors(topColorLeft, topColorRight, topColorCenter);
                        }

                        final int statusBarOverrideColor = mStatusFilterEnabled ?
                                filter(tmp, -10) : tmp;
                        if (mStatusBarOverrideColor != statusBarOverrideColor) {
                            updateStatusBarColor(statusBarOverrideColor);

                            // magic from http://www.w3.org/TR/AERT#color-contrast
                            final float statusBarBrightness =
                                    (0.299f * Color.red(statusBarOverrideColor) +
                                    0.587f * Color.green(statusBarOverrideColor) +
                                    0.114f * Color.blue(statusBarOverrideColor)) / 255;
                            updateStatusBarIconColor(statusBarBrightness > 0.7f &&
                                    isStatusBarConsistent ? 0x95000000 : 0xFFFFFFFF);
                        }
                    } else {
                        // dynamic status bar is disabled
                        updateStatusBarColor(0);
                        updateStatusBarIconColor(0);
                    }

                    if (mNavigationEnabled) {
                        final int navigationBarOverrideColor;
                        if (botColorLeft == botColorRight) {
                            // navigation bar appears to be completely uniform
                            navigationBarOverrideColor = botColorLeft;
                        } else if (botColorLeft == botColorCenter ||
                                botColorRight == botColorCenter) {
                            // a side of the navigation bar appears to be uniform
                            navigationBarOverrideColor = botColorCenter;
                        } else {
                            // navigation bar does not appear to be uniform at all
                            navigationBarOverrideColor = sampleColors(botColorLeft, botColorRight,
                                botColorCenter);
                        }

                        if (mNavigationBarOverrideColor != navigationBarOverrideColor) {
                            updateNavigationBarColor(navigationBarOverrideColor);

                            // magic from http://www.w3.org/TR/AERT#color-contrast
                            final float navigationBarBrightness =
                                    (0.299f * Color.red(navigationBarOverrideColor) +
                                    0.587f * Color.green(navigationBarOverrideColor) +
                                    0.114f * Color.blue(navigationBarOverrideColor)) / 255;
                            updateNavigationBarIconColor(navigationBarBrightness > 0.7f &&
                                    isNavigationBarConsistent ? 0x95000000 : 0xFFFFFFFF);
                        }
                    } else {
                        // dynamic navigation bar is disabled
                        updateNavigationBarColor(0);
                        updateNavigationBarIconColor(0);
                    }
                } else {
                    // we are disabled completely - shush
                    updateStatusBarColor(0);
                    updateStatusBarIconColor(0);
                    updateNavigationBarColor(0);
                    updateNavigationBarIconColor(0);
                }

                // do a quick cleanup of the listener list
                synchronized(BarBackgroundUpdater.class) {
                    final ArrayList<UpdateListener> removables = new ArrayList<UpdateListener>();

                    for (final UpdateListener listener : mListeners) {
                        if (listener.shouldGc()) {
                            removables.add(listener);
                        }
                    }

                    for (final UpdateListener removable : removables) {
                        mListeners.remove(removable);
                    }
                }

                try {
                    Thread.sleep(DELAY_IN_MILLIS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }

    });

    static {
        THREAD.start();
    }

    private static boolean mStatusEnabled = false;
    private static boolean mStatusFilterEnabled = false;
    private static int mStatusBarOverrideColor = 0;
    private static int mStatusBarIconOverrideColor = 0;

    private static boolean mNavigationEnabled = false;
    private static int mNavigationBarOverrideColor = 0;
    private static int mNavigationBarIconOverrideColor = 0;

    private static Context mContext = null;
    private static ArrayList<UpdateListener> mListeners = new ArrayList<UpdateListener>();
    private static SettingsObserver mObserver = null;

    private BarBackgroundUpdater() {
    }

    public synchronized static void init(final Context context) {
        if (mContext != null) {
            mContext.unregisterReceiver(RECEIVER);

            if (mObserver != null) {
                mContext.getContentResolver().unregisterContentObserver(mObserver);
            }
        }

        mContext = context;

        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        mContext.registerReceiver(RECEIVER, filter);

        if (mObserver == null) {
            mObserver = new SettingsObserver(new Handler());
        }

        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.DYNAMIC_STATUS_BAR_STATE),
                false, mObserver, UserHandle.USER_ALL);
        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.DYNAMIC_NAVIGATION_BAR_STATE),
                false, mObserver, UserHandle.USER_ALL);
        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.DYNAMIC_STATUS_BAR_FILTER_STATE),
                false, mObserver, UserHandle.USER_ALL);

        mStatusEnabled = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.DYNAMIC_STATUS_BAR_STATE, 0) == 1;
        mNavigationEnabled = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.DYNAMIC_NAVIGATION_BAR_STATE, 0) == 1;
        mStatusFilterEnabled = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.DYNAMIC_STATUS_BAR_FILTER_STATE, 0) == 1;
    }

    public synchronized static void addListener(final UpdateListener... listeners) {
        for (final UpdateListener listener : listeners) {
            if (listener == null) {
                continue;
            }

            if (mStatusBarOverrideColor == 0) {
                listener.onResetStatusBarColor();
            } else {
                listener.onUpdateStatusBarColor(mStatusBarOverrideColor);
            }

            if (mStatusBarIconOverrideColor == 0) {
                listener.onResetStatusBarIconColor();
            } else {
                listener.onUpdateStatusBarIconColor(mStatusBarIconOverrideColor);
            }

            if (mNavigationBarOverrideColor == 0) {
                listener.onResetNavigationBarColor();
            } else {
                listener.onUpdateNavigationBarColor(mNavigationBarOverrideColor);
            }

            if (mNavigationBarIconOverrideColor == 0) {
                listener.onResetNavigationBarIconColor();
            } else {
                listener.onUpdateNavigationBarIconColor(mNavigationBarIconOverrideColor);
            }

            boolean shouldAdd = true;

            for (final UpdateListener existingListener : mListeners) {
                if (existingListener == listener) {
                    shouldAdd = false;
                }
            }

            if (shouldAdd) {
                mListeners.add(listener);
            }
        }
    }

    private static int filter(final int original, final float diff) {
        final int red = (int) (Color.red(original) + diff);
        final int green = (int) (Color.green(original) + diff);
        final int blue = (int) (Color.blue(original) + diff);

        return Color.argb(
                Color.alpha(original),
                red > 0 ?
                        red < 255 ?
                                red :
                                255 :
                        0,
                green > 0 ?
                        green < 255 ?
                                green :
                                255 :
                        0,
                blue > 0 ?
                        blue < 255 ?
                                blue :
                                255 :
                        0
        );
    }

    private static int sampleColors(final int... originals) {
        final int n = originals.length;

        float alpha = 0;
        float red = 0;
        float green = 0;
        float blue = 0;

        for (final int original : originals) {
            alpha += Color.alpha(original) / n;
            red += Color.red(original) / n;
            green += Color.green(original) / n;
            blue += Color.blue(original) / n;
        }

        return Color.argb((int) alpha, (int) red, (int) green, (int) blue);
    }

    private static int getPixel(final Bitmap bitmap, final int x, final int y) {
        if (bitmap == null) {
            // just silently ignore this
            return Color.BLACK;
        }

        if (x == 0) {
            Log.w(LOG_TAG, "getPixel for x=0 is not allowed; returning a black pixel");
            return Color.BLACK;
        }

        if (y == 0) {
            Log.w(LOG_TAG, "getPixel for y=0 is not allowed; returning a black pixel");
            return Color.BLACK;
        }

        return bitmap.getPixel(x > 0 ? x : bitmap.getWidth() + x,
            y > 0 ? y : bitmap.getHeight() + y);
    }

    public synchronized static void updateStatusBarColor(final int newColor) {
        if (mStatusBarOverrideColor == newColor) {
            return;
        }

        mStatusBarOverrideColor = newColor;

        if (DEBUG) {
            Log.d(LOG_TAG, "statusBarOverrideColor=" + (newColor == 0 ? "none" :
                    "0x" + Integer.toHexString(newColor)));
        }

        for (final UpdateListener listener : mListeners) {
            if (newColor == 0) {
                listener.onResetStatusBarColor();
            } else {
                listener.onUpdateStatusBarColor(newColor);
            }
        }
    }

    public synchronized static void updateStatusBarIconColor(final int newColor) {
        if (mStatusBarIconOverrideColor == newColor) {
            return;
        }

        mStatusBarIconOverrideColor = newColor;

        if (DEBUG) {
            Log.d(LOG_TAG, "statusBarIconOverrideColor=" + (newColor == 0 ? "none" :
                    "0x" + Integer.toHexString(newColor)));
        }

        for (final UpdateListener listener : mListeners) {
            if (newColor == 0) {
                listener.onResetStatusBarIconColor();
            } else {
                listener.onUpdateStatusBarIconColor(newColor);
            }
        }
    }

    public synchronized static void updateNavigationBarColor(final int newColor) {
        if (mNavigationBarOverrideColor == newColor) {
            return;
        }

        mNavigationBarOverrideColor = newColor;

        if (DEBUG) {
            Log.d(LOG_TAG, "navigationBarOverrideColor=" + (newColor == 0 ? "none" :
                    "0x" + Integer.toHexString(newColor)));
        }

        for (final UpdateListener listener : mListeners) {
            if (newColor == 0) {
                listener.onResetNavigationBarColor();
            } else {
                listener.onUpdateNavigationBarColor(newColor);
            }
        }
    }

    public synchronized static void updateNavigationBarIconColor(final int newColor) {
        if (mNavigationBarIconOverrideColor == newColor) {
            return;
        }

        mNavigationBarIconOverrideColor = newColor;

        if (DEBUG) {
            Log.d(LOG_TAG, "navigationBarIconOverrideColor=" + (newColor == 0 ? "none" :
                    "0x" + Integer.toHexString(newColor)));
        }

        for (final UpdateListener listener : mListeners) {
            if (newColor == 0) {
                listener.onResetNavigationBarIconColor();
            } else {
                listener.onUpdateNavigationBarIconColor(newColor);
            }
        }
    }

    public static class UpdateListener {
        private final WeakReference<Object> mRef;

        public UpdateListener(final Object ref) {
            mRef = new WeakReference<Object>(ref);
        }

        public final boolean shouldGc() {
            return mRef.get() == null;
        }

        public void onResetStatusBarColor() {
        }

        public void onUpdateStatusBarColor(final int color) {
        }

        public void onResetStatusBarIconColor() {
        }

        public void onUpdateStatusBarIconColor(final int iconColor) {
        }

        public void onResetNavigationBarColor() {
        }

        public void onUpdateNavigationBarColor(final int color) {
        }

        public void onResetNavigationBarIconColor() {
        }

        public void onUpdateNavigationBarIconColor(final int iconColor) {
        }
    }

    private static final class SettingsObserver extends ContentObserver {
        private SettingsObserver(final Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(final boolean selfChange) {
            mStatusEnabled = Settings.System.getInt(mContext.getContentResolver(),
                    Settings.System.DYNAMIC_STATUS_BAR_STATE, 0) == 1;
            mNavigationEnabled = Settings.System.getInt(mContext.getContentResolver(),
                    Settings.System.DYNAMIC_NAVIGATION_BAR_STATE, 0) == 1;
            mStatusFilterEnabled = Settings.System.getInt(mContext.getContentResolver(),
                    Settings.System.DYNAMIC_STATUS_BAR_FILTER_STATE, 0) == 1;
        }
    }

}
