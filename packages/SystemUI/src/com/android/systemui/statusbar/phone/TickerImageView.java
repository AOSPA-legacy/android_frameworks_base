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

import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.PorterDuff;
import android.os.Handler;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageSwitcher;
import android.widget.ImageView;

import com.android.systemui.statusbar.phone.BarBackgroundUpdater;

public class TickerImageView extends ImageSwitcher {
    private final Handler mHandler;
    private int mOverrideIconColor = 0;

    public TickerImageView(final Context context, final AttributeSet attrs) {
        super(context, attrs);
        mHandler = new Handler();
        BarBackgroundUpdater.addListener(new BarBackgroundUpdater.UpdateListener(this) {

            @Override
            public void onResetStatusBarIconColor() {
                mOverrideIconColor = 0;
                mHandler.post(new Runnable() {

                    @Override
                    public void run() {
                        final int childCount = getChildCount();
                        for (int i = 0; i < childCount; i++) {
                            final ImageView iv = (ImageView) getChildAt(i);
                            if (iv != null) {
                                iv.setColorFilter(null);
                            }
                        }
                    }

                });
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
                                //Imageviews have no way to get the current color filter, store the last one in a tag
                                Integer currentColor = (Integer) iv.getTag();
                                if (currentColor == null) currentColor = new Integer(0); // On the first pass the tag is null
                                ObjectAnimator.ofObject(iv, "tint", new ArgbEvaluator(), currentColor.intValue(), mOverrideIconColor)
	                                .setDuration(500)
                                    .start();
                                iv.setTag(new Integer(mOverrideIconColor));
                            }
                        }
                    }

                });
            }

        });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addView(final View child, final int index, final ViewGroup.LayoutParams params) {
        if (child instanceof ImageView) {
            if (mOverrideIconColor == 0) {
                ((ImageView) child).setColorFilter(null);
            } else {
                ((ImageView) child).setColorFilter(mOverrideIconColor,
                        PorterDuff.Mode.MULTIPLY);
            }
        }

        super.addView(child, index, params);
    }

}
