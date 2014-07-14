package com.android.systemui.recent;

import java.util.ArrayList;
import java.util.List;

import com.android.systemui.R;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.content.Context;
import android.content.res.Configuration;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.widget.OverScroller;
import android.widget.RelativeLayout;

/*
* Some explanation:
* Each item stores a linear position between [-L,H] where L is the landing area
* (decelerate movement) and H is the height of the view. To achieve a smooth
* 'card stack effect', those linear scroll positions are scaled to produce the
* real positions in the view. Therefore, the head space of length 2*L is scaled
* down to length L by applying the parabola function x^2 in the domain [0,0.5].
*
* The distance (scroll positions) between the first items is computed by
* applying the inverse function. Hereby, all items have the same distance in
* view when scroll position is 0 (i.e. no cards are on the top stack). For
* movement, there is a global scroll position (basically an offset) that is
* subtracted from each item's scroll position for before computing the scaled
* position in the view.
*
* To match input events (view positions) with the linear position, the
* inverse function is also applied to input coordinates before adjusting
* the global scroll position.
*
* Distance depends on the number of elements (D=H/x), but cannot be less than
* MIN_DISTANCE for layouting reasons. On item remove all global lengths
* (D, scroll length) are recomputed.
*
*
* Diagram:
*                                           x ^
*                                             |                       .'
*                                             |                     .'  linear
*             ---                ---   item3 -|-------------------.'
* bottom cap > |                  D           |                 .' |
*              |                  |           |               .'   |
*              |                 ---   item2 -|-------------.'     |
*         view length   --- - - - - - - - - - | - - - - - .' |     |
*              |         |                    |        _-''  |     |
*              |         L             item1 -|-----_.'   '  |     |
*              |         |                    |_..-'|     '  |     |
*              v         v        _____....--'|     |     '  |     |
*          -----------------------------------+-------------------------->
*                                 |           |     |     '  |     |     y
*                               item0         |   item1   'item2 item3
*                                             |           '
*                                 |<----L---->|<----L---->|  |<-D->|
*                                             |
*
*                                             |<---view length---->|
*
*                                 |<--------scroll length--------->|
*                  scroll position ^
*
*
* TODOs:
*   - make mOverScrollPosition to be only within [-mMaxOverScroll,mMaxOverScroll]
*/

public class CardStackView extends RelativeLayout {
    public static final String TAG = "CardStackView";

    public static final int PORTRAIT = 0;
    public static final int LANDSCAPE = 1;

    /* Lengths in percent of view height */
    private static final float MIN_DISTANCE = 0.2f;
    private static final float MAX_OVERSCROLL = 0.2f;

    /* Landing area defines the area where interpolation is applied */
    private static final float MAX_LANDING_AREA = 0.5f;
    private static final boolean LANDING_EQUAL_DISTANCE = false;

    /* Maximum tilt of items */
    private static final float MAX_TILT = 5.f;

    private static final int REMOVE_ANIMATION_DURATION = 1000;

    protected Context mContext;
    protected int mOrientation;
    private int mWidth;
    private int mHeight;
    private int mViewLength;

    private int mScrollLength;
    private int mScrollPosition = 0;
    private int mMaxOverScroll;
    private int mOverScrollPosition = 0;
    private boolean mIsScrolling;
    private boolean mIsFlinging;

    private float mInitTouchPos;
    private float mPagingTouchSlop;

    private int mDistance;
    private int mLandingArea;
    private float mTilt;
    private int mBottomCap;

    private Animator mRemoveAnimator;

    private int mLastViewTouch;
    private float mLastScrollTouch;
    private int mDeltaToScrollAnchor;

    // OverScroller and GestureDector for handling fling scrolling (measuring
    // scroll velocity on TOUCH_UP and computing decelerating scroll animation).
    private GestureDetector mDetector;
    private OverScroller mScroller;

    protected List<CardStackViewItem> mItems =
            new ArrayList<CardStackViewItem>();

    private PositionCalculator mCalculator = new PositionCalculator();

    /*
     * Calculates positions of items in scroll coordinate system.
     */
    class PositionCalculator {
        /* Position in view coordinate system */
        private int mPosition = 0;

        public void reset() {
            // First element always at 0
            mPosition = 0;
        }

        public int getNextPosition() {
            try {
                // Convert current view position to scroll position
                return (int)viewPositionToScrollPosition(mPosition, true);
            } finally {
                // Item are always positioned equidistantly in view
                mPosition += mDistance;
            }
        }
    }

    /*
     * Abstract OverScroll effect. Handles OverScrolling and animation of reset.
     * The actual effect is defined by implementing the abstract method computeOverScrolling.
     */
    private abstract class OverScrollEffect {
        private static final int OVERSCROLL_ANIMATION_DURATION = 500;
        private float mCurrentProgress;
        private ObjectAnimator mOverscrollAnimator;

        public void setProgress(float progress) {
            if (progress < -1.0f) {
                progress = -1.0f;
            } else if (progress > 1.0f) {
                progress = 1.0f;
            }
            mCurrentProgress = progress;
        }

        public void update() {
            computeOverScrolling(mCurrentProgress);
        }

        public void stopAnimation() {
            if (mOverscrollAnimator != null) {
                mOverscrollAnimator.cancel();
            }
        }

        public void animateReset() {
            if (mOverscrollAnimator != null) {
                mOverscrollAnimator.cancel();
            }

            mOverscrollAnimator = ObjectAnimator.ofFloat(this, "progress", mCurrentProgress, 0.0f);
            mOverscrollAnimator.setDuration((int)(Math.abs(mCurrentProgress) * OVERSCROLL_ANIMATION_DURATION));
            mOverscrollAnimator.setInterpolator(new DecelerateInterpolator());
            mOverscrollAnimator.addListener(new Animator.AnimatorListener() {
                @Override
                public void onAnimationStart(Animator animator) {
                }

                @Override
                public void onAnimationEnd(Animator animator) {
                    mOverscrollAnimator = null;
                    update();
                }

                @Override
                public void onAnimationCancel(Animator animator) {
                    mOverscrollAnimator = null;
                    update();
                }

                @Override
                public void onAnimationRepeat(Animator animator) {
                }
            });
            mOverscrollAnimator.addUpdateListener(new AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator valueAnimator) {
                    update();
                }
            });
            mOverscrollAnimator.start();
        }

        public void reset() {
            mCurrentProgress = 0.0f;
        }

        /*
         * Compute OverScroll effect.
         */
        protected abstract void computeOverScrolling(float progress);
    }

    private OverScrollEffect mTiltEffect = new OverScrollEffect() {
        /* Tilt on OverScroll */
        @Override
        protected void computeOverScrolling(float progress) {
            // Compute tilt
            mTilt = progress * MAX_TILT;

            // Update position
            mOverScrollPosition = mScrollPosition + (int)(progress * mMaxOverScroll);

            // Update layout and child view positions
            updateLayout();
        }
    };

    private final static int CARD_PADDING = 15;
    private int mCardPaddingPixel;

    public int getCardWidth(boolean withPadding) {
        int width;

        DisplayMetrics dm = getResources().getDisplayMetrics();
        if (mOrientation == PORTRAIT) {
            width = dm.widthPixels;
        } else {
            width = dm.heightPixels + mCardPaddingPixel*2;
        }

        if (!withPadding) {
            width -= mCardPaddingPixel*2;
        }

        return width;
    }

    public int getCardHeight(boolean withPadding) {
        int height;

        DisplayMetrics dm = getResources().getDisplayMetrics();
        if (mOrientation == PORTRAIT) {
            height = dm.widthPixels + mCardPaddingPixel*2;
        } else {
            height = dm.heightPixels;
        }

        if (!withPadding) {
            height -= mCardPaddingPixel*2;
        }

        return height;
    }

    public void createDummy() {
        /*for (int i = 0; i < 10; i++) {
            LayoutInflater inflater = (LayoutInflater) mContext
                .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            View dummyCard = inflater.inflate(R.layout.status_bar_recent_card, this, false);

            CardStackViewItem item =
                    new CardStackViewItem(mContext, mOrientation);
            item.setContentView(dummyCard, getCardWidth(true), getCardHeight(true));
            mItems.add(item);
            addView(item, positionView(item.getPosition()));
        }*/
    }

    public CardStackView(Context context, int orientation) {
        super(context);

        mContext = context;
        mOrientation = orientation;
        mPagingTouchSlop = ViewConfiguration.get(context).getScaledPagingTouchSlop();
        mCardPaddingPixel = (int)TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, CARD_PADDING, getResources().getDisplayMetrics());

        createDummy();

        mDetector = new GestureDetector(context, mGestureListener);
        mScroller = new OverScroller(context);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mPagingTouchSlop = ViewConfiguration.get(mContext).getScaledPagingTouchSlop();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        mWidth = w;
        mHeight = h;

        update();
    }

    private int scrollPositionOfMostRecent() {
        if (mItems.size() < 2) {
            return 0;
        } else {
            return mScrollLength - mLandingArea - mBottomCap;
        }
    }

    public void update() {
        mViewLength = mOrientation == PORTRAIT ? mHeight : mWidth;

        updateLengths();

        mBottomCap = mViewLength - mDistance;

        updatePositions();

        // Start position: scroll to end of items
        mOverScrollPosition = mScrollPosition = scrollPositionOfMostRecent();

        updateLayout();
    }

    private void updateLengths() {
        if (mItems.size() < 2) {
            mLandingArea = 0;
            mScrollLength = 0;
        } else {
            if (mItems.size() < 1/MIN_DISTANCE) {
                mDistance = mViewLength / mItems.size();
            } else {
                mDistance = (int)(mViewLength * MIN_DISTANCE);
                if (mDistance > mViewLength) {
                    mDistance = mViewLength;
                }
            }

            if (LANDING_EQUAL_DISTANCE) {
                mLandingArea = mDistance;
            } else {
                mLandingArea = (int)(mViewLength * MAX_LANDING_AREA);
                if (mLandingArea > mViewLength) {
                    mLandingArea = mViewLength;
                }
            }

            mScrollLength = mDistance * (mItems.size()-1) + mLandingArea;
        }

        mMaxOverScroll = (int)(mViewLength * MAX_OVERSCROLL);
    }

    private void updatePositions() {
        mCalculator.reset();
        for (CardStackViewItem item : mItems) {
            item.setPosition(mCalculator.getNextPosition());
        }

        updateLayout();
    }

    private int scrollPositionToViewPosition(int position) {
        position -= mScrollPosition;
        if (position < mLandingArea && position > -mLandingArea) {
            float fraction = (float)(position+mLandingArea) / (2*mLandingArea);
            position = (int)(fraction*fraction*mLandingArea);
        }

        if (position < 0) {
            position = 0;
        } else if (position > mBottomCap) {
            position = mBottomCap;
        }

        return position;
    }

    private float viewPositionToScrollPosition(float position, boolean ignoreLength) {
        if (position < mLandingArea) {
            float fraction = (float)position / mLandingArea;
            position = (float)(Math.sqrt(fraction)*mLandingArea*2)-mLandingArea;
        }

        if (position < -mLandingArea) {
            position = -mLandingArea;
        } else if (position > mViewLength && !ignoreLength) {
            position = mViewLength;
        }

        return position;
    }

    private boolean isOccluded(CardStackViewItem prev, CardStackViewItem next) {
        if (next.getPosition() + mLandingArea >= mScrollPosition) {
            // card 'next' is not on top
            if (prev.getPosition() >=  mScrollPosition + mBottomCap) {
                // card 'prev' is on bottom and probably occluded
                // (except if it is the last card on stack)
                return true;
            } else {
                // card 'prev' is not occluded
                return false;
            }

        } else {
            // card 'next' is on top and therefore occluding 'prev'
            return true;
        }
    }

    private boolean isOccluded(int index) {
        if (mItems.size() - index > 1) {
            return isOccluded(mItems.get(index), mItems.get(index + 1));
        }
        return false;
    }

    private void updateLayout() {
        CardStackViewItem prev = null;

        for (CardStackViewItem next : mItems) {
            if (prev != null) {
                if (!isOccluded(prev, next)) {
                    layoutItem(prev);
                } else {
                    // causes cards to appear delayed
                    prev.setVisibility(INVISIBLE);
                }
            }
            prev = next;
        }

        // always show last card on stack
        if (prev != null) {
            layoutItem(prev);
        }
    }

    private void layoutItem(CardStackViewItem item) {
        item.setVisibility(VISIBLE);
        item.setTilt(mTilt);
        item.setLayoutParams(
                positionView(scrollPositionToViewPosition(item.getPosition())));
        item.invalidate();
    }

    protected RelativeLayout.LayoutParams positionView(int position) {
        RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(
                getCardWidth(true), getCardHeight(true));
        lp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        if (mOrientation == PORTRAIT) {
            lp.leftMargin = 0;
            lp.topMargin = position;
        } else {
            lp.leftMargin = position;
            lp.topMargin = 0;
        }
        return lp;
    }

    private void doScrolling(int pos) {
        mOverScrollPosition = pos;
        if (mOverScrollPosition < 0) {
            mScrollPosition = 0;
            mTiltEffect.setProgress((float) mOverScrollPosition / mMaxOverScroll);
            mTiltEffect.update();
        } else if (mOverScrollPosition > mScrollLength) {
            mScrollPosition = mScrollLength;
            mTiltEffect.setProgress((float) (mOverScrollPosition - mScrollLength) / mMaxOverScroll);
            mTiltEffect.update();
        } else {
            mScrollPosition = mOverScrollPosition;
            mTiltEffect.reset();
            mTiltEffect.update();
        }
    }

    public boolean isScrolling() {
        return mIsScrolling;
    }

    public int getChildIdAtViewPosition(float position, boolean ignoreOcclusion) {
        int id = -1;
        if (mItems.size() > 0) {
            // Before converting to scroll position, transform to touch position by subtracting
            // start padding. Expect all items to have the same start padding.
            position -= mItems.get(0).getStartPadding();

            // Check if we touched 'before' first card.
            if (position > 0) {
                // Convert to scroll position before comparing item positions.
                float pos = viewPositionToScrollPosition(position, true);

                if (pos > mBottomCap && !ignoreOcclusion) {
                    id = mItems.size() - 1;
                } else {
                    pos += mScrollPosition;
                    int i = 0;
                    for (CardStackViewItem item : mItems) {
                        if (item.getPosition() > pos) {
                            break;
                        }
                        id = i++;
                    }
                }
            }
        }
        return id;
    }

    private void animateRemoval(View view) {
        AnimatorSet set = new AnimatorSet();
        List<Animator> list = new ArrayList<Animator>();

        mCalculator.reset();
        for (final CardStackViewItem item : mItems) {
            int oldPos = item.getPosition();
            int newPos = mCalculator.getNextPosition();

            if (scrollPositionToViewPosition(oldPos) == scrollPositionToViewPosition(newPos)) {
                // Item is at top or bottom and does not move => no need for animation
                item.setPosition(newPos);
            } else {
                ObjectAnimator animator =
                        ObjectAnimator.ofInt(item, "position", oldPos, newPos);
                animator.addUpdateListener(new AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator animation) {
                        layoutItem(item);
                    }
                });
                list.add(animator);
            }
        }

        set.playTogether(list);
        set.setDuration(REMOVE_ANIMATION_DURATION);
        set.setInterpolator(new DecelerateInterpolator());
        set.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mRemoveAnimator = null;
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                mRemoveAnimator = null;
            }
        });
        set.start();
        mRemoveAnimator = set;
    }

    public void removeItem(View view) {
        if (mRemoveAnimator != null) {
            mRemoveAnimator.cancel();
        }

        removeView(view);
        mItems.remove(view);
        updateLengths();

        if (mItems.size() > 0) {
            animateRemoval(view);
        }
    }

    protected void resetOverscrolling() {
        if (mOverScrollPosition < 0) {
            mOverScrollPosition = 0;
            mTiltEffect.animateReset();
        } else if (mOverScrollPosition > mScrollLength) {
            mOverScrollPosition = mScrollLength;
            mTiltEffect.animateReset();
        }
    }

    protected float getPos(MotionEvent event) {
        return mOrientation == PORTRAIT ? event.getY() : event.getX();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        float curTouchPos = getPos(ev);

        switch (ev.getAction()) {
            case MotionEvent.ACTION_DOWN:
                mIsScrolling = false;
                mInitTouchPos = curTouchPos;
                break;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(mInitTouchPos-curTouchPos) > mPagingTouchSlop) {
                    mIsScrolling = true;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mIsScrolling = false;
                break;
        }
        return mIsScrolling;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        super.onTouchEvent(event);
        mDetector.onTouchEvent(event);

        float curViewTouch = getPos(event);

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                mTiltEffect.stopAnimation();
                int index = getChildIdAtViewPosition(curViewTouch, true);
                if (index >= 0) {
                    mLastScrollTouch = mItems.get(index).getPosition();
                    mDeltaToScrollAnchor = (int)curViewTouch -
                            scrollPositionToViewPosition((int)mLastScrollTouch);
                    mLastScrollTouch -= mScrollPosition;
                }
                mLastViewTouch = (int)(curViewTouch - mDeltaToScrollAnchor);
                break;

            case MotionEvent.ACTION_MOVE:
                int pos;
                float curScrollTouch;

                // (1) Move touch event in view coordinate system to anchor of
                // current item and (2) convert to scroll coordinate system
                curViewTouch -= mDeltaToScrollAnchor;

                // Convert to scroll coordinate system and compute delta for
                // scrolling. If card is already on top position, simply use
                // delta from view coordinate system.
                if (curViewTouch > 0) {
                    // Convert to scroll coordinate
                    curScrollTouch = viewPositionToScrollPosition(curViewTouch, false);
                    pos = (int)(mLastScrollTouch - curScrollTouch);
                    mLastScrollTouch = curScrollTouch;
                } else {
                    // Use delta from view coordinates
                    pos = (int)(mLastViewTouch - curViewTouch);
                }

                doScrolling(mOverScrollPosition + pos);
                mLastViewTouch = (int)curViewTouch;
                break;

            case MotionEvent.ACTION_UP:
                if (!mIsFlinging) {
                    // We don't need to reset OverScroll position if the view is currently flinging.
                    // When flinging, the OverScroller (@see mScroller) takes care of resetting and
                    // animating OverScroll positions (@see computeScroll())
                    resetOverscrolling();
                }
                updateLayout();
                break;
        }

        return mIsScrolling;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        super.dispatchTouchEvent(event);

        // Handle event here instead of using an OnTouchListener
        onTouchEvent(event);

        return true;
    }

    private final GestureDetector.SimpleOnGestureListener mGestureListener
            = new GestureDetector.SimpleOnGestureListener() {
        @Override
        public boolean onDown(MotionEvent e) {
            mScroller.forceFinished(true);
            mIsFlinging = false;
            return true;
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2,
                float velocityX, float velocityY) {
            if (mOverScrollPosition >= 0 && mOverScrollPosition < mScrollLength) {
                // Only fling if we're not at the edge already
                mIsFlinging = true;
                fling((int) -velocityX, (int) -velocityY);
            }
            return true;
        }
    };

    private void fling(int velocityX, int velocityY) {
        mScroller.forceFinished(true);

        // OverScroller usually handles 2D, but we need only 1D. Therefore, use
        // only Y-dimension and map it accordingly to vertical or horizontal
        // view.
        mScroller.fling(0, mScrollPosition,
                0, mOrientation == PORTRAIT ? velocityY : velocityX,
                0, 0,
                0, mScrollLength,
                0, mMaxOverScroll);
    }

    @Override
    public void computeScroll() {
        // The scroller isn't finished, meaning a fling or programmatic pan
        // operation is currently active.
        if (mScroller.computeScrollOffset()) {
            doScrolling(mScroller.getCurrY());
        }
    }
}
