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

package com.android.systemui.statusbar.policy;

import android.content.Context;
import android.os.Handler;
import android.util.AttributeSet;

import com.android.systemui.statusbar.phone.BarBackgroundUpdater;

/**
 * Digital clock exclusively for the phone status bar.
 */
public class PhoneStatusBarClock extends Clock implements BarBackgroundUpdater.UpdateListener {
    private final Handler mHandler;

    private Integer mOverrideTextColor = null;

    public PhoneStatusBarClock(final Context context) {
        this(context, null);
    }

    public PhoneStatusBarClock(final Context context, final AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PhoneStatusBarClock(final Context context, final AttributeSet attrs,
            final int defStyle) {
        super(context, attrs, defStyle);

        mHandler = new Handler();
        BarBackgroundUpdater.addListener(this);
    }

    @Override
    public void onUpdateStatusBarColor(final Integer color) {
        // noop
    }

    @Override
    public void onUpdateStatusBarIconColor(final Integer iconColor) {
        mOverrideTextColor = iconColor;
        mHandler.post(new Runnable() {

            @Override
            public void run() {
                // TODO themeability - use the resource instead of a hardcoded value
                setTextColor(mOverrideTextColor == null ? 0xffffffff : mOverrideTextColor);
            }

        });
    }

    @Override
    public void onUpdateNavigationBarColor(final Integer color) {
        // noop
    }

    @Override
    public void onUpdateNavigationBarIconColor(final Integer iconColor) {
        // noop
    }

}
