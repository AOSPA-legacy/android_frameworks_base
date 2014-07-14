package com.android.systemui.recent;

import com.android.systemui.R;
import com.android.systemui.SwipeHelper;
import com.android.systemui.recent.RecentsPanelView.TaskDescriptionAdapter;

import android.content.Context;
import android.content.res.Configuration;
import android.database.DataSetObserver;
import android.graphics.Canvas;
import android.os.Handler;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.Toast;

public class RecentsCardStackView extends CardStackView implements View.OnClickListener,
        View.OnLongClickListener, SwipeHelper.Callback, RecentsPanelView.RecentsScrollView {

    private int mLastViewTouch;
    private boolean mIsSwiping;
    private boolean mClearAllAnimationDone;
    private Handler mHandler = new Handler();

    // SwipeHelper for handling swipe to dismiss.
    private SwipeHelper mSwipeHelper;

    // RecentScrollView implementation for interaction with parent
    // RecentsPanelView.
    private RecentsCallback mCallback;
    private TaskDescriptionAdapter mAdapter;

    public RecentsCardStackView(Context context, int orientation) {
        super(context, orientation);

        setOnClickListener(this);
        setOnLongClickListener(this);

        setBackgroundResource(R.drawable.status_bar_recents_background);

        float densityScale = getResources().getDisplayMetrics().density;
        float pagingTouchSlop = ViewConfiguration.get(context).getScaledPagingTouchSlop();
        mSwipeHelper = new SwipeHelper(
                mOrientation == PORTRAIT ? SwipeHelper.X : SwipeHelper.Y,
                this, densityScale, pagingTouchSlop);

        mClearAllAnimationDone = true;
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        float densityScale = getResources().getDisplayMetrics().density;
        mSwipeHelper.setDensityScale(densityScale);
        float pagingTouchSlop = ViewConfiguration.get(mContext).getScaledPagingTouchSlop();
        mSwipeHelper.setPagingTouchSlop(pagingTouchSlop);
    }

    @Override
    public void onClick(View view) {
        if (!isScrolling() && !mIsSwiping) {
            int viewId = getChildIdAtViewPosition(mLastViewTouch, false);

            // TODO: Remove this
            //Toast.makeText(mContext, "Clicked item: " + viewId, Toast.LENGTH_SHORT).show();

            if (viewId >= 0 && mCallback != null) {
                mCallback.handleOnClick(mItems.get(viewId).getContentView());
            }
        }
    }

    @Override
    public boolean onLongClick(View view) {
        if (!isScrolling() && !mIsSwiping) {
            int viewId = getChildIdAtViewPosition(mLastViewTouch, false);

            // TODO: Remove this
            //Toast.makeText(mContext, "Long pressed item: " + viewId, Toast.LENGTH_SHORT).show();

            if (viewId >= 0 && mCallback != null) {
                View contentView = mItems.get(viewId).getContentView();
                RecentsPanelView.ViewHolder holder = (RecentsPanelView.ViewHolder) contentView.getTag();
                final View thumbnailView = holder.thumbnailView;
                mCallback.handleLongPress(contentView, thumbnailView, thumbnailView);
                return true;
            }
        }
        return false;
    }

    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return mSwipeHelper.onInterceptTouchEvent(ev) ||
                super.onInterceptTouchEvent(ev);
    }

    private void dismissChild(View v) {
        mSwipeHelper.dismissChild(v, 0);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        mLastViewTouch = (int)getPos(ev);
        mIsSwiping = mSwipeHelper.onTouchEvent(ev);
        return mIsSwiping ||
                super.onTouchEvent(ev);
    }

    @Override
    public View getChildAtPosition(MotionEvent ev) {
        int id = getChildIdAtViewPosition(
                getPos(ev), false);

        CardStackViewItem view = null;
        if (id >= 0) {
            view = mItems.get(id);
        }
        return view;
    }

    @Override
    public View getChildContentView(View v) {
        return v;
    }

    @Override
    public boolean canChildBeDismissed(View v) {
        return true;
    }

    @Override
    public void onBeginDrag(View v) {
        // We do this so the underlying ScrollView knows that it won't get
        // the chance to intercept events anymore
        requestDisallowInterceptTouchEvent(true);
        v.setActivated(true);
    }

    @Override
    public void onChildDismissed(View v) {
        removeItem(v);
        resetOverscrolling();
        if (mCallback != null) {
            mCallback.handleSwipe(((CardStackViewItem)v).getContentView());
        }
    }

    @Override
    public void onDragCancelled(View v) {
        v.setActivated(false);
        resetOverscrolling();
    }

    @Override
    public int numItemsInOneScreenful() {
        return mItems.size();
    }

    private void updateAdapter() {
        DisplayMetrics dm = getResources().getDisplayMetrics();

        for (int i = 0; i < mAdapter.getCount(); ++i) {
            Log.d(TAG, "Added adapter view " + i);

            // Create our own view item
            CardStackViewItem item = null;

            if (i < mItems.size()) {
                item = mItems.get(i);
            } else {
                item = new CardStackViewItem(mContext, mOrientation);
                addView(item, positionView(0));
                mItems.add(item);
            }

            // Let adapter create a view and add to item
            View child = mAdapter.getView(i, item.getContentView(), item);
            item.setTag(child.getTag());
            if (item.getContentView() == null) {
                item.setContentView(child, getCardWidth(true), getCardHeight(true));
            }
        }
        update();
    }

    @Override
    public void setAdapter(TaskDescriptionAdapter adapter) {
        Log.d(TAG, "Added adapter with size " + adapter.getCount());
        mAdapter = adapter;
        mAdapter.registerDataSetObserver(new DataSetObserver() {
            public void onChanged() {
                updateAdapter();
            }

            public void onInvalidated() {
                updateAdapter();
            }
        });

        updateAdapter();
    }

    @Override
    public void setCallback(RecentsCallback callback) {
        mCallback = callback;
    }

    @Override
    public void setMinSwipeAlpha(float minAlpha) {
        mSwipeHelper.setMinAlpha(minAlpha);
    }

    @Override
    public View findViewForTask(int persistentTaskId) {
        for (CardStackViewItem item : mItems) {
            View view = item.getContentView();
            RecentsPanelView.ViewHolder holder = (RecentsPanelView.ViewHolder) view.getTag();
            if (holder.taskDescription.persistentTaskId == persistentTaskId) {
                return view;
            }
        }
        return null;
    }

    @Override
    public void drawFadedEdges(Canvas c, int left, int right, int top,
            int bottom) {
        // TODO Auto-generated method stub

    }

    @Override
    public void setOnScrollListener(Runnable listener) {
        // TODO Auto-generated method stub
    }

    @Override
    public void swipeAllViewsInLayout() {
        Thread clearAll = new Thread(new Runnable() {
            @Override
            public void run() {
                int count = mItems.size();
                // if we have more than one app, don't kill the current one
                if(count > 1) count--;
                View[] refView = new View[count];
                for (int i = 0; i < count; i++) {
                    refView[i] = mItems.get(i);
                }
                for (int i = 0; i < count; i++) {
                    final View child = refView[i];
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            dismissChild(child);
                        }
                    });
                    try {
                        Thread.sleep(150);
                    } catch (InterruptedException e) {
                        // User will see the app fading instantly after the previous
                        // one. This will probably never happen
                    }
                }
                // we're done dismissing childs here, reset
                mClearAllAnimationDone = true;
            }
        });

        if (mClearAllAnimationDone) {
            mClearAllAnimationDone = false;
            clearAll.start();
        }
    }
}
