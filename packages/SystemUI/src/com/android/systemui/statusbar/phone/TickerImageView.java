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
import android.graphics.PorterDuff;
import android.os.Handler;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageSwitcher;
import android.widget.ImageView;

import com.android.systemui.statusbar.phone.BarBackgroundUpdater;

public class TickerImageView extends ImageSwitcher implements BarBackgroundUpdater.UpdateListener {
    private final Handler mHandler;
    private int mOverrideIconColor = BarBackgroundUpdater.NO_OVERRIDE;

    public TickerImageView(final Context context, final AttributeSet attrs) {
        super(context, attrs);
        mHandler = new Handler();
        BarBackgroundUpdater.addListener(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addView(final View child, final int index, final ViewGroup.LayoutParams params) {
        if (child instanceof ImageView) {
            if (mOverrideIconColor == BarBackgroundUpdater.NO_OVERRIDE) {
                ((ImageView) child).setColorFilter(null);
            } else {
                ((ImageView) child).setColorFilter(mOverrideIconColor,
                        PorterDuff.Mode.MULTIPLY);
            }
        }

        super.addView(child, index, params);
    }

    @Override
    public void onUpdateStatusBarColor(final int color) {
        // noop
    }

    @Override
    public void onUpdateStatusBarIconColor(final int iconColor) {
        mOverrideIconColor = iconColor;
        mHandler.post(new Runnable() {

            @Override
            public void run() {
                final int childCount = getChildCount();
                for (int i = 0; i < childCount; i++) {
                    final ImageView iv = (ImageView) getChildAt(i);
                    if (iv != null) {
                        if (mOverrideIconColor == BarBackgroundUpdater.NO_OVERRIDE) {
                            iv.setColorFilter(null);
                        } else {
                            iv.setColorFilter(mOverrideIconColor, PorterDuff.Mode.MULTIPLY);
                        }
                    }
                }
            }

        });
    }

    @Override
    public void onUpdateNavigationBarColor(final int color) {
        // noop
    }

    @Override
    public void onUpdateNavigationBarIconColor(final int iconColor) {
        // noop
    }
}

