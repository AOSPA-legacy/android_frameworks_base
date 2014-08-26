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

    private final static int MAX_DELAY_IN_MILLIS = 1000;
    private final static int DELAY_IN_MILLIS = 1000 / 15;
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
            int delayInMillis = DELAY_IN_MILLIS;

            while (true) {
                while (PAUSED) {
                    // we have been told to do nothing; wait for a second

                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        return;
                    }
                }

                if (DELAY_IN_MILLIS <= 0) {
                    // we have been told to delay for a weird timespan; retry in a second

                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        return;
                    }

                    continue;
                }

                final Context context = mContext;

                if (context == null) {
                    // we haven't been initiated yet; retry in a bit

                    try {
                        Thread.sleep(delayInMillis = DELAY_IN_MILLIS);
                    } catch (InterruptedException e) {
                        return;
                    }

                    continue;
                }

                final Integer statusBarOverrideColor;
                final Integer statusBarIconOverrideColor;
                final Integer navigationBarOverrideColor;
                final Integer navigationBarIconOverrideColor;

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
                            Thread.sleep(delayInMillis = DELAY_IN_MILLIS);
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

                    final Bitmap rawScreen = SurfaceControl.screenshot(width, height);
                    if (rawScreen == null) {
                        // something went wrong during the screenshot grabbing; retry in a bit

                        try {
                            Thread.sleep(delayInMillis = DELAY_IN_MILLIS);
                        } catch (InterruptedException e) {
                            return;
                        }

                        continue;
                    }

                    final Bitmap screenshot = Bitmap.createBitmap(width, height,
                        rawScreen.getConfig());

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

                        statusBarOverrideColor = mStatusFilterEnabled ? filter(tmp, -10) : tmp;

                        // magic from the suggestion at http://www.w3.org/TR/AERT#color-contrast
                        final float statusBarBrightness =
                            (0.299f * Color.red(statusBarOverrideColor) +
                            0.587f * Color.green(statusBarOverrideColor) +
                            0.114f * Color.blue(statusBarOverrideColor)) / 255;
                        statusBarIconOverrideColor = statusBarBrightness > 0.7f &&
                            isStatusBarConsistent ? 0x95000000 : 0xFFFFFFFF;
                    } else {
                        statusBarOverrideColor = null;
                        statusBarIconOverrideColor = null;
                    }

                    if (mNavigationEnabled) {
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

                        // magic from the suggestion at http://www.w3.org/TR/AERT#color-contrast
                        final float navigationBarBrightness =
                            (0.299f * Color.red(navigationBarOverrideColor) +
                            0.587f * Color.green(navigationBarOverrideColor) +
                            0.114f * Color.blue(navigationBarOverrideColor)) / 255;
                        navigationBarIconOverrideColor = navigationBarBrightness > 0.7f &&
                            isNavigationBarConsistent ? 0x95000000 : 0xFFFFFFFF;
                    } else {
                        navigationBarOverrideColor = null;
                        navigationBarIconOverrideColor = null;
                    }
                } else {
                    statusBarOverrideColor = null;
                    statusBarIconOverrideColor = null;
                    navigationBarOverrideColor = null;
                    navigationBarIconOverrideColor = null;
                }

                // do a quick cleanup of the listener list
                synchronized(BarBackgroundUpdater.class) {
                    final ArrayList<WeakReference<UpdateListener>> removables =
                        new ArrayList<WeakReference<UpdateListener>>();

                    for (final WeakReference<UpdateListener> listener : mListeners) {
                        if (listener.get() == null) {
                            removables.add(listener);
                        }
                    }

                    for (final WeakReference<UpdateListener> removable : removables) {
                        mListeners.remove(removable);
                    }
                }

                boolean anythingUpdated = false;

                // update the status bar itself, if needed
                if (mStatusBarOverrideColor != statusBarOverrideColor) {
                    anythingUpdated = true;
                    synchronized(BarBackgroundUpdater.class) {
                        mStatusBarOverrideColor = statusBarOverrideColor;

                        if (DEBUG) {
                            Log.d(LOG_TAG, "statusBarOverrideColor=" +
                                (mStatusBarOverrideColor == null ? "none" :
                                    "0x" + Integer.toHexString(mStatusBarOverrideColor)));
                        }

                        for (final WeakReference<UpdateListener> listener : mListeners) {
                            final UpdateListener l = listener.get();
                            if (l != null) {
                                l.onUpdateStatusBarColor(mStatusBarOverrideColor);
                            }
                        }
                    }
                }

                // update the status bar icons, if needed
                if (mStatusBarIconOverrideColor != statusBarIconOverrideColor) {
                    anythingUpdated = true;
                    synchronized(BarBackgroundUpdater.class) {
                        mStatusBarIconOverrideColor = statusBarIconOverrideColor;

                        if (DEBUG) {
                            Log.d(LOG_TAG, "statusBarIconOverrideColor=" +
                                (mStatusBarIconOverrideColor == null ? "none" :
                                    "0x" + Integer.toHexString(mStatusBarIconOverrideColor)));
                        }

                        for (final WeakReference<UpdateListener> listener : mListeners) {
                            final UpdateListener l = listener.get();
                            if (l != null) {
                                l.onUpdateStatusBarIconColor(mStatusBarIconOverrideColor);
                            }
                        }
                    }
                }

                // update the navigation bar itself, if needed
                if (mNavigationBarOverrideColor != navigationBarOverrideColor) {
                    anythingUpdated = true;
                    synchronized(BarBackgroundUpdater.class) {
                        mNavigationBarOverrideColor = navigationBarOverrideColor;

                        if (DEBUG) {
                            Log.d(LOG_TAG, "navigationBarOverrideColor=" +
                                (mNavigationBarOverrideColor == null ? "none" :
                                    "0x" + Integer.toHexString(mNavigationBarOverrideColor)));
                        }

                        for (final WeakReference<UpdateListener> listener : mListeners) {
                            final UpdateListener l = listener.get();
                            if (l != null) {
                                l.onUpdateNavigationBarColor(mNavigationBarOverrideColor);
                            }
                        }
                    }
                }

                // update the navigation bar icons, if needed
                if (mNavigationBarIconOverrideColor != navigationBarIconOverrideColor) {
                    anythingUpdated = true;
                    synchronized(BarBackgroundUpdater.class) {
                        mNavigationBarIconOverrideColor = navigationBarIconOverrideColor;

                        if (DEBUG) {
                            Log.d(LOG_TAG, "navigationBarIconOverrideColor=" +
                                (mNavigationBarIconOverrideColor == null ? "none" :
                                    "0x" + Integer.toHexString(mNavigationBarIconOverrideColor)));
                        }

                        for (final WeakReference<UpdateListener> listener : mListeners) {
                            final UpdateListener l = listener.get();
                            if (l != null) {
                                l.onUpdateNavigationBarIconColor(mNavigationBarIconOverrideColor);
                            }
                        }
                    }
                }

                if (anythingUpdated) {
                    delayInMillis = DELAY_IN_MILLIS;
                } else {
                    delayInMillis *= 2;
                    if (delayInMillis > MAX_DELAY_IN_MILLIS) {
                        delayInMillis = MAX_DELAY_IN_MILLIS;
                    }
                }

                try {
                    Thread.sleep(delayInMillis);
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
    private static Integer mStatusBarOverrideColor = null;
    private static Integer mStatusBarIconOverrideColor = null;

    private static boolean mNavigationEnabled = false;
    private static Integer mNavigationBarOverrideColor = null;
    private static Integer mNavigationBarIconOverrideColor = null;

    private static Context mContext = null;
    private static ArrayList<WeakReference<UpdateListener>> mListeners =
        new ArrayList<WeakReference<UpdateListener>>();
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
        for (final UpdateListener l : listeners) {
            if (l == null) {
                continue;
            }

            l.onUpdateStatusBarColor(mStatusBarOverrideColor);
            l.onUpdateStatusBarIconColor(mStatusBarIconOverrideColor);

            l.onUpdateNavigationBarColor(mNavigationBarOverrideColor);
            l.onUpdateNavigationBarIconColor(mNavigationBarIconOverrideColor);

            boolean shouldAdd = true;

            for (final WeakReference<UpdateListener> existingListener : mListeners) {
                if (existingListener.get() == l) {
                    shouldAdd = false;
                }
            }

            if (shouldAdd) {
                mListeners.add(new WeakReference<UpdateListener>(l));
            }
        }
    }

    private static Integer filter(final Integer original, final float diff) {
        if (original == null) {
            return null;
        }

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

    private static Integer sampleColors(final Integer... originals) {
        final int n = originals.length;

        float alpha = 0;
        float red = 0;
        float green = 0;
        float blue = 0;

        for (final Integer original : originals) {
            if (original != null) {
                alpha += Color.alpha(original) / n;
                red += Color.red(original) / n;
                green += Color.green(original) / n;
                blue += Color.blue(original) / n;
            }
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

    public static interface UpdateListener {
        public void onUpdateStatusBarColor(final Integer color);

        public void onUpdateStatusBarIconColor(final Integer iconColor);

        public void onUpdateNavigationBarColor(final Integer color);

        public void onUpdateNavigationBarIconColor(final Integer iconColor);
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
