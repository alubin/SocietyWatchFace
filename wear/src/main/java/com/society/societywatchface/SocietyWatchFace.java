/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.society.societywatchface;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.TypedValue;
import android.view.SurfaceHolder;

import java.lang.ref.WeakReference;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Analog watch face with a ticking second hand. In ambient mode, the second hand isn't shown. On
 * devices with low-bit ambient mode, the hands are drawn without anti-aliasing in ambient mode.
 */
public class SocietyWatchFace extends CanvasWatchFaceService {
    /**
     * Update rate in milliseconds for interactive mode. We update once a second to advance the
     * second hand.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SocietyWatchFace.Engine> mWeakReference;

        public EngineHandler(SocietyWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SocietyWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine {
        private final Resources mResources = getResources();
        private Paint mFilterPaint;
        private final Handler mUpdateTimeHandler = new EngineHandler(this);
        private boolean mRegisteredTimeZoneReceiver = false;
        private Paint mBackgroundPaint;
        private Paint mHandPaint;
        private boolean mAmbient;
        private Time mTime;
        private final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };
        private int mTapCount;
        private Paint mAmbientBackgroundPaint;
        private Paint mAmbientPeekCardBorderPaint;

        private final Rect mCardBounds = new Rect();

        private static final float BORDER_WIDTH_PX = 3.0f;

        // Variables for onDraw
        private int mWidth = -1;
        private int mHeight = -1;
        private float mScale;
        private float mCenterX;
        private float mCenterY;
        private int mMinutes;
        private float mMinDeg;
        private float mHrDeg;
        private Bitmap mMinHand;
        private Bitmap mHrHand;
        private Bitmap mFace;
        private float mScaledXOffset;
        private float mScaledXAdditionalOffset;
        private float mScaledYOffset;
        private long mTimeElapsed;
        private int mLoop;
        private float mRadius;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        private boolean mLowBitAmbient;

        private Bitmap[] mBackgroundBitmap;
        private Bitmap[] mFaceBitmap;
        private Bitmap[] mHourHandBitmap;
        private Bitmap[] mMinuteHandBitmap;



        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SocietyWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_SHORT)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());

            Resources resources = SocietyWatchFace.this.getResources();

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mHandPaint = new Paint();
            mHandPaint.setColor(resources.getColor(R.color.analog_hands));
            mHandPaint.setStrokeWidth(resources.getDimension(R.dimen.analog_hand_stroke));
            mHandPaint.setAntiAlias(true);
            mHandPaint.setStrokeCap(Paint.Cap.ROUND);

            mTime = new Time();

            init();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private void init()
        {
            // Pre-loading bitmaps
            mBackgroundBitmap = loadBitmaps(R.array.backgroundIds);
            mFaceBitmap = loadBitmaps(R.array.faceIds);
            mHourHandBitmap = loadBitmaps(R.array.hourIds);
            mMinuteHandBitmap = loadBitmaps(R.array.minuteIds);

            // Initialising paint object for Bitmap draws
            mFilterPaint = new Paint();
            mFilterPaint.setFilterBitmap(true);

            // Initialising background paint
            mAmbientBackgroundPaint = new Paint();
            mAmbientBackgroundPaint.setARGB(255, 0, 0, 0);
            mAmbientPeekCardBorderPaint = new Paint();
            mAmbientPeekCardBorderPaint.setColor(Color.WHITE);
            mAmbientPeekCardBorderPaint.setStrokeWidth(BORDER_WIDTH_PX);

            // Initialising time
            mTime = new Time();
        }

        /**
         * Loading all versions (interactive, ambient and low bit) into a bitmap array. The correct
         * version will be pluck out at runtime.
         *
         * @param arrayId Key to the type of bitmap that we are initialising. The full list can be
         *                found in res/values/images_santa_watchface.xml
         * @return Array of three bitmaps for interactive, ambient and low bit modes
         */
        private Bitmap[] loadBitmaps(int arrayId) {
            int[] bitmapIds = getIntArray(arrayId);
            Bitmap[] bitmaps = new Bitmap[bitmapIds.length];
            for (int i = 0; i < bitmapIds.length; i++) {
                Drawable backgroundDrawable = mResources.getDrawable(bitmapIds[i]);
                bitmaps[i] = ((BitmapDrawable) backgroundDrawable).getBitmap();
            }
            return bitmaps;
        }

        /**
         * At runtime, this is used to load the appropriate bitmap depending on display mode
         * dynamically.
         *
         * @param bitmaps A bitmap array containing all bitmaps appropriate to all the display
         *                modes
         * @return Bitmap determined to be appropriate for the display mode
         */
        private Bitmap getBitmap(Bitmap[] bitmaps) {
            if (!mAmbient) {
                return bitmaps[0];
            } else if (!mLowBitAmbient) {
                return bitmaps[1];
            } else {
                return bitmaps[2];
            }
        }

        /**
         * Scale bitmap array in place.
         *
         * @param bitmaps Bitmaps to be scaled
         * @param scale   Scale factor. 1.0 represents the original size.
         */
        private void scaleBitmaps(Bitmap[] bitmaps, float scale) {
            for (int i = 0; i < bitmaps.length; i++) {
                bitmaps[i] = scaleBitmap(bitmaps[i], scale);
            }
        }

        /**
         * Scale individual bitmap inputs by creating a new bitmap according to the scale
         *
         * @param bitmap Original bitmap
         * @param scale  Scale factor. 1.0 represents the original size.
         * @return Scaled bitmap
         */
        private Bitmap scaleBitmap(Bitmap bitmap, float scale) {
            int width = (int) ((float) bitmap.getWidth() * scale);
            int height = (int) ((float) bitmap.getHeight() * scale);
            if (bitmap.getWidth() != width
                    || bitmap.getHeight() != height) {
                return Bitmap.createScaledBitmap(bitmap,
                        width, height, true /* filter */);
            } else {
                return bitmap;
            }
        }

        /**
         * Loading an int array from resource file
         *
         * @param resId ResourceId of the integer array
         * @return int array
         */
        private int[] getIntArray(int resId) {
            TypedArray array = mResources.obtainTypedArray(resId);
            int[] rc = new int[array.length()];
            TypedValue value = new TypedValue();
            for (int i = 0; i < array.length(); i++) {
                array.getValue(i, value);
                rc[i] = value.resourceId;
            }
            return rc;
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mHandPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            Resources resources = SocietyWatchFace.this.getResources();
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
//                    mTapCount++;
//                    mBackgroundPaint.setColor(resources.getColor(mTapCount % 2 == 0 ?
//                            R.color.background : R.color.background2));
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            mTime.setToNow();

            //Draw background.
            canvas.drawRect(0, 0, mWidth, mHeight, mAmbientBackgroundPaint);
            canvas.drawBitmap(getBitmap(mBackgroundBitmap), 0, 0, mFilterPaint);



            // Handle Ambient events
            if (mAmbient) {
                // Draw a black box as the peek card background
                canvas.drawRect(mCardBounds, mAmbientBackgroundPaint);
            }

//            if (isInAmbientMode()) {
//                canvas.drawColor(Color.BLACK);
//            } else {
//                canvas.drawRect(0, 0, canvas.getWidth(), canvas.getHeight(), mBackgroundPaint);
//            }

            mMinutes = mTime.minute;
            mMinDeg = mMinutes * 6;
            mHrDeg = ((mTime.hour + (mMinutes / 60f)) * 30);

            canvas.save();

            // Draw the minute hand
            canvas.rotate(mMinDeg, mCenterX, mCenterY);
            mMinHand = getBitmap(mMinuteHandBitmap);
            canvas.drawBitmap(mMinHand, mCenterX - mMinHand.getWidth() / 2f,
                    mCenterY - mMinHand.getHeight(), mFilterPaint);

            // Draw the hour hand
            canvas.rotate(360 - mMinDeg + mHrDeg, mCenterX, mCenterY);
            mHrHand = getBitmap(mHourHandBitmap);
            canvas.drawBitmap(mHrHand, mCenterX - mHrHand.getWidth() / 2f,
                    mCenterY - mHrHand.getHeight(),
                    mFilterPaint);

            canvas.restore();

            // Draw face.  (We do this last so it's not obscured by the arms.)
            mFace = getBitmap(mFaceBitmap);
            canvas.drawBitmap(mFace,
                    mCenterX - mFace.getWidth() / 2 + mScaledXOffset,
                    mCenterY - mFace.getHeight() / 2 + mScaledYOffset,
                    mFilterPaint);

            // While watch face is active, immediately request next animation frame.
            if (isVisible() && !isInAmbientMode()) {
                invalidate();
            }
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);

            mWidth = width;
            mHeight = height;

            // Find the center. Ignore the window insets so that, on round watches with a
            // "chin", the watch face is centered on the entire screen, not just the usable
            // portion.
            mCenterX = mWidth / 2f;
            mCenterY = mHeight / 2f;

            mScale = ((float) mWidth) / (float) mBackgroundBitmap[0].getWidth();
            scaleBitmaps(mBackgroundBitmap, mScale);
            scaleBitmaps(mFaceBitmap, mScale);
            scaleBitmaps(mHourHandBitmap, mScale);
            scaleBitmaps(mMinuteHandBitmap, mScale);

            mScaledXOffset = mScale;
            mScaledYOffset = mScale;

        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SocietyWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SocietyWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }
    }
}
