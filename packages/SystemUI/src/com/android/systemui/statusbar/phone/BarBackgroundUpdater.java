/*
 * Copyright (C) 2014 Valter Strods for ParanoidAndroid Project
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

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Point;
import android.provider.Settings;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.WindowManager;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

public class BarBackgroundUpdater {
    public final static int NO_OVERRIDE = -1;

    private final static boolean DEBUG = false;
    private final static String LOG_TAG = BarBackgroundUpdater.class.getSimpleName();

    private final static int DELAY_IN_MILLIS = 1000 / 30;

    private final static Thread THREAD = new Thread(new Runnable() {

        @Override
        public void run() {
            while (true) {
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
                        Thread.sleep(DELAY_IN_MILLIS);
                    } catch (InterruptedException e) {
                        return;
                    }

                    continue;
                }

                final int statusBarOverrideColor;
                final int statusBarIconOverrideColor;
                final int navigationBarOverrideColor;
                final int navigationBarIconOverrideColor;

                // TODO switch these over to static values that get updated by an observer
                final boolean statusEnabled = Settings.System.getInt(context.getContentResolver(),
                    Settings.System.DYNAMIC_STATUS_BAR_STATE, 0) == 1;
                final boolean navigationEnabled = Settings.System.getInt(
                    context.getContentResolver(), Settings.System.DYNAMIC_NAVIGATION_BAR_STATE,
                    0) == 1;

                if (statusEnabled || navigationEnabled) {
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

                    if (navigationBarHeight <= 0 && navigationEnabled) {
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

                    // TODO actually fix rotation - possibly see the activity manager for this
                    final Bitmap screenshot;
                    if (isLandscape) {
                        // we have to manually handle landscape since it is not handled by the API
                        screenshot = SurfaceControl.screenshot(p.y, p.x);
                    } else {
                        screenshot = SurfaceControl.screenshot(p.x, p.y);
                    }

                    if (screenshot == null) {
                        // something went wrong during the screenshot grabbing; retry in a bit

                        try {
                            Thread.sleep(DELAY_IN_MILLIS);
                        } catch (InterruptedException e) {
                            return;
                        }

                        continue;
                    }

                    final int sbColorOne = getPixel(screenshot, rotation, 1, 1);
                    final int sbColorTwo = getPixel(screenshot, rotation, 1, 5);

                    final int nbColorOne = getPixel(screenshot, rotation, -1, -1);
                    final int nbColorTwo = getPixel(screenshot, rotation, -1, -5);

                    final int topLeftColor = getPixel(screenshot, rotation,
                        1, yBelowStatusBar);
                    final int topRightColor = getPixel(screenshot, rotation,
                        xForStatusBar, yBelowStatusBar);
                    final int topCenterColor = getPixel(screenshot, rotation,
                        (int) (xForStatusBar / 2), yBelowStatusBar);

                    final int botLeftColor = getPixel(screenshot, rotation,
                        1, yAboveNavigationBar);
                    final int botRightColor = getPixel(screenshot, rotation,
                        xForStatusBar, yAboveNavigationBar);
                    final int botCenterColor = getPixel(screenshot, rotation,
                        (int) (xForStatusBar / 2), yAboveNavigationBar);

                    // clean up now since no more colors are needed
                    screenshot.recycle();

                    final boolean isStatusBarConsistent = sbColorOne == sbColorTwo;
                    final boolean isNavigationBarConsistent = nbColorOne == nbColorTwo;

                    // TODO implement status bar color darkening based on user configuration
                    if (statusEnabled) {
                        if (topLeftColor == topRightColor) {
                            // status bar appears to be uniform
                            // TODO investigate why full white fails to apply
                            statusBarOverrideColor = topLeftColor == 0xFFFFFFFF ?
                                0xFFFEFEFE : topLeftColor;
                        } else {
                            // status bar does not appear to be uniform
                            // TODO investigate why full white fails to apply
                            int override = sampleColors(topLeftColor, topRightColor, topCenterColor);
                            statusBarOverrideColor = override == 0xFFFFFFFF ? 0xFFFEFEFE : override;
                        }

                        final float statusBarBrightness =
                            (0.299f * Color.red(statusBarOverrideColor) +
                            0.587f * Color.green(statusBarOverrideColor) +
                            0.114f * Color.blue(statusBarOverrideColor)) / 255;
                        statusBarIconOverrideColor = statusBarBrightness > 0.7f &&
                            isStatusBarConsistent ? 0x95000000 : 0xFFFFFFFF;
                    } else {
                        statusBarOverrideColor = NO_OVERRIDE;
                        statusBarIconOverrideColor = NO_OVERRIDE;
                    }

                    if (navigationEnabled) {
                        if (botLeftColor == botRightColor) {
                            // navigation bar appears to be uniform
                            // TODO investigate why full white fails to apply
                            navigationBarOverrideColor = botLeftColor == 0xFFFFFFFF ?
                                0xFFFEFEFE : botLeftColor;
                        } else {
                            // navigation bar does not appear to be uniform
                            // TODO investigate why full white fails to apply
                            int override = sampleColors(botLeftColor, botRightColor, botCenterColor);
                            navigationBarOverrideColor = override == 0xFFFFFFFF ?
                                0xFFFEFEFE : override;
                        }

                        final float navigationBarBrightness =
                            (0.299f * Color.red(navigationBarOverrideColor) +
                            0.587f * Color.green(navigationBarOverrideColor) +
                            0.114f * Color.blue(navigationBarOverrideColor)) / 255;
                        navigationBarIconOverrideColor = navigationBarBrightness > 0.7f &&
                            isNavigationBarConsistent ? 0x95000000 : 0xFFFFFFFF;
                    } else {
                        navigationBarOverrideColor = NO_OVERRIDE;
                        navigationBarIconOverrideColor = NO_OVERRIDE;
                    }
                } else {
                    statusBarOverrideColor = NO_OVERRIDE;
                    statusBarIconOverrideColor = NO_OVERRIDE;
                    navigationBarOverrideColor = NO_OVERRIDE;
                    navigationBarIconOverrideColor = NO_OVERRIDE;
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

                // update the status bar itself, if needed
                if (mStatusBarOverrideColor != statusBarOverrideColor) {
                    synchronized(BarBackgroundUpdater.class) {
                        mStatusBarOverrideColor = statusBarOverrideColor;

                        if (DEBUG) {
                            Log.d(LOG_TAG, "statusBarOverrideColor=" +
                                (mStatusBarOverrideColor == NO_OVERRIDE ? "NO_OVERRIDE" :
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
                    synchronized(BarBackgroundUpdater.class) {
                        mStatusBarIconOverrideColor = statusBarIconOverrideColor;

                        if (DEBUG) {
                            Log.d(LOG_TAG, "statusBarIconOverrideColor=0x" +
                                (mStatusBarIconOverrideColor == NO_OVERRIDE ? "NO_OVERRIDE" :
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
                    synchronized(BarBackgroundUpdater.class) {
                        mNavigationBarOverrideColor = navigationBarOverrideColor;

                        if (DEBUG) {
                            Log.d(LOG_TAG, "navigationBarOverrideColor=0x" +
                                (mNavigationBarOverrideColor == NO_OVERRIDE ? "NO_OVERRIDE" :
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
                    synchronized(BarBackgroundUpdater.class) {
                        mNavigationBarIconOverrideColor = navigationBarIconOverrideColor;

                        if (DEBUG) {
                            Log.d(LOG_TAG, "navigationBarIconOverrideColor=0x" +
                                (mNavigationBarIconOverrideColor == NO_OVERRIDE ? "NO_OVERRIDE" :
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

    private static int mStatusBarOverrideColor = NO_OVERRIDE;
    private static int mStatusBarIconOverrideColor = NO_OVERRIDE;

    private static int mNavigationBarOverrideColor = NO_OVERRIDE;
    private static int mNavigationBarIconOverrideColor = NO_OVERRIDE;

    private static Context mContext = null;
    private static ArrayList<WeakReference<UpdateListener>> mListeners =
        new ArrayList<WeakReference<UpdateListener>>();

    private BarBackgroundUpdater() {
    }

    public synchronized static void init(final Context context) {
        mContext = context;
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

    private static int getPixel(final Bitmap bitmap, final int rotation,
            final int x, final int y) {
        if (bitmap == null) {
            // just silently ignore this
            return 0xFF000000;
        }

        if (x == 0) {
            Log.w(LOG_TAG, "getPixel for x=0 is not allowed; returning a black pixel");
            return 0xFF000000;
        }

        if (y == 0) {
            Log.w(LOG_TAG, "getPixel for y=0 is not allowed; returning a black pixel");
            return 0xFF000000;
        }

        if (rotation == Surface.ROTATION_180) {
            // turned upside down; invert all the things
            return bitmap.getPixel(x > 0 ? bitmap.getWidth() + x : x,
                y > 0 ? bitmap.getHeight() + y : y);
        }

        if (rotation == Surface.ROTATION_90) {
            // turned counter-clockwise; invert some of the things
            return bitmap.getPixel(x > 0 ? x : bitmap.getWidth() + x,
                y > 0 ? y : bitmap.getHeight() + y);
        }

        if (rotation == Surface.ROTATION_270) {
            // turned clockwise; invert some of the things
            return bitmap.getPixel(x > 0 ? x : bitmap.getWidth() + x,
                y > 0 ? y : bitmap.getHeight() + y);
        }

        if (rotation != Surface.ROTATION_0) {
            Log.w(LOG_TAG, "getPixel for rotation=" + Integer.toString(rotation) +
                " is assuming natural orientation");
        }

        return bitmap.getPixel(x > 0 ? x : bitmap.getWidth() + x,
            y > 0 ? y : bitmap.getHeight() + y);
    }

    public static interface UpdateListener {
        public void onUpdateStatusBarColor(final int color);

        public void onUpdateStatusBarIconColor(final int iconColor);

        public void onUpdateNavigationBarColor(final int color);

        public void onUpdateNavigationBarIconColor(final int iconColor);
    }

}
