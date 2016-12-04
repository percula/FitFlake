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

package com.perculacreative.peter.wearable.snowflakewatchface;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v7.graphics.Palette;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.fitness.Fitness;
import com.google.android.gms.fitness.FitnessStatusCodes;
import com.google.android.gms.fitness.data.DataType;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Sample digital watch face with blinking colons and seconds. In ambient mode, the seconds are
 * replaced with an AM/PM indicator and the colons don't blink. On devices with low-bit ambient
 * mode, the text is drawn without anti-aliasing in ambient mode. On devices which require burn-in
 * protection, the hours are drawn in normal rather than bold. The time is drawn with less contrast
 * and without seconds in mute mode.
 */
public class SnowWatchFaceService extends CanvasWatchFaceService {
    private static final String TAG = "SnowWatchFaceService";

    private static final Typeface BOLD_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for normal (not ambient and not mute) mode. We update twice
     * a second to blink the colons.
     */
    private static final long NORMAL_UPDATE_RATE_MS = 1000;

    /**
     * Update rate in milliseconds for mute mode. We update every minute, like in ambient mode.
     */
    private static final long MUTE_UPDATE_RATE_MS = TimeUnit.MINUTES.toMillis(1);

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private class Engine extends CanvasWatchFaceService.Engine implements
            DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener{
        static final String COLON_STRING = ":";

        /** Alpha value for drawing time when in mute mode. */
        static final int MUTE_ALPHA = 100;

        /** Alpha value for drawing time when not in mute mode. */
        static final int NORMAL_ALPHA = 255;

        static final int MSG_UPDATE_TIME = 0;

        /** How often {@link #mUpdateTimeHandler} ticks in milliseconds. */
        long mInteractiveUpdateRateMs = NORMAL_UPDATE_RATE_MS;

        /** Handler to update the time periodically in interactive mode. */
        final Handler mUpdateTimeHandler = new Handler() {
            @Override
            public void handleMessage(Message message) {
                switch (message.what) {
                    case MSG_UPDATE_TIME:
                        if (Log.isLoggable(TAG, Log.VERBOSE)) {
                            Log.v(TAG, "updating time");
                        }
                        invalidate();
                        if (shouldTimerBeRunning()) {
                            long timeMs = System.currentTimeMillis();
                            long delayMs =
                                    mInteractiveUpdateRateMs - (timeMs % mInteractiveUpdateRateMs);
                            mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
                        }
                        break;
                }
            }
        };

        GoogleApiClient mGoogleApiClient = new GoogleApiClient.Builder(SnowWatchFaceService.this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();

        /**
         * Handles time zone and locale changes.
         */
        final BroadcastReceiver mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                initFormats();
                invalidate();
            }
        };

        /**
         * Unregistering an unregistered receiver throws an exception. Keep track of the
         * registration state to prevent that.
         */
        boolean mRegisteredReceiver = false;

        private Bitmap mBackgroundBitmap;
        private Bitmap mGrayBackgroundBitmap;

        Paint mBackgroundPaint;

        private Paint mHandPaint;
        private Paint mHourHandPaint;
        private Paint mSnowflakePaint;

        private int mWatchHandColor;
        private int mWatchRestHandColor;
        private int mWatchHandShadowColor;

        private float mHourHandRadius;
        private float mMinuteHandLength;
        private float mSecondHandLength;

        private int mCount;

        private int mWidth;
        private int mHeight;
        private float mCenterX;
        private float mCenterY;
        private float mScale = 1;

        private static final float HAND_END_CAP_RADIUS = 4f;
        private static final float STROKE_WIDTH = 6f;
        private static final float STROKE_REST_WIDTH = 4f;

        private static final int SHADOW_RADIUS = 6;
        private static final float HOUR_STROKE_WIDTH = 5f;
        private static final float MINUTE_STROKE_WIDTH = 3f;
        private static final float SECOND_TICK_STROKE_WIDTH = 2f;
        private static final float CENTER_GAP_AND_CIRCLE_RADIUS = 30f;
        private static final float mHourScaleFactor = 1.1f;
        private Rect mCardBounds = new Rect();

        Paint mDatePaint;
        Paint mHourPaint;
        Paint mMinutePaint;
        Paint mSecondPaint;
        Paint mAmPmPaint;
        Paint mColonPaint;
        float mColonWidth;
        boolean mMute;

        Calendar mCalendar;
        Date mDate;
        SimpleDateFormat mDayOfWeekFormat;
        java.text.DateFormat mDateFormat;

        boolean mShouldDrawColons;
        float mXOffset;
        float mYOffset;
        float mLineHeight;
        String mAmString;
        String mPmString;
        int mInteractiveBackgroundColor =
                SnowWatchFaceUtil.COLOR_VALUE_DEFAULT_AND_AMBIENT_BACKGROUND;
        int mInteractiveHourDigitsColor =
                SnowWatchFaceUtil.COLOR_VALUE_DEFAULT_AND_AMBIENT_HOUR_DIGITS;
        int mInteractiveMinuteDigitsColor =
                SnowWatchFaceUtil.COLOR_VALUE_DEFAULT_AND_AMBIENT_MINUTE_DIGITS;
        int mInteractiveSecondDigitsColor =
                SnowWatchFaceUtil.COLOR_VALUE_DEFAULT_AND_AMBIENT_SECOND_DIGITS;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;
        /**
         * Whether the display supports burn in protection in ambient mode.
         * When true, remove the background in ambient mode.
         */
        private boolean mBurnInProtection;
        private boolean mAmbient;

        private GoogleApiClient mStepsGoogleApiClient;

        private boolean mStepsRequested;

        private int mStepsTotal = 0;


        @Override
        public void onCreate(SurfaceHolder holder) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onCreate");
            }
            super.onCreate(holder);

//            mStepsRequested = false;
//            mStepsGoogleApiClient = new GoogleApiClient.Builder(SnowWatchFaceService.this)
//                    .addConnectionCallbacks(this)
//                    .addOnConnectionFailedListener(this)
//                    .addApi(Fitness.HISTORY_API)
//                    .addApi(Fitness.RECORDING_API)
//                    // When user has multiple accounts, useDefaultAccount() allows Google Fit to
//                    // associated with the main account for steps. It also replaces the need for
//                    // a scope request.
//                    .useDefaultAccount()
//                    .build();

            setWatchFaceStyle(new WatchFaceStyle.Builder(SnowWatchFaceService.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());
            Resources resources = SnowWatchFaceService.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);
            mLineHeight = resources.getDimension(R.dimen.digital_line_height);
            mAmString = resources.getString(R.string.digital_am);
            mPmString = resources.getString(R.string.digital_pm);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(mInteractiveBackgroundColor);
            mDatePaint = createTextPaint(resources.getColor(R.color.digital_date));
            mHourPaint = createTextPaint(mInteractiveHourDigitsColor, BOLD_TYPEFACE);
            mMinutePaint = createTextPaint(mInteractiveMinuteDigitsColor);
            mSecondPaint = createTextPaint(mInteractiveSecondDigitsColor);
            mAmPmPaint = createTextPaint(resources.getColor(R.color.digital_am_pm));
            mColonPaint = createTextPaint(resources.getColor(R.color.digital_colons));

            // Set paint for hands
            mHandPaint = new Paint();
            mHandPaint.setColor(Color.WHITE);
            mHandPaint.setStrokeWidth(STROKE_WIDTH);
            mHandPaint.setAntiAlias(true);
            mHandPaint.setStrokeCap(Paint.Cap.ROUND);
            mHandPaint.setStyle(Paint.Style.STROKE);
            mHandPaint.setShadowLayer(SHADOW_RADIUS, 0, 0, Color.GRAY);

            // Make hour hand a little thicker so it's visible when they align
            mHourHandPaint = new Paint(mHandPaint);
            mHourHandPaint.setStrokeWidth(mHourScaleFactor * STROKE_WIDTH);
            mHourHandPaint.setShadowLayer((mHourScaleFactor * SHADOW_RADIUS), 0, 0, Color.GRAY);

            // Set paint for snowflake
            mSnowflakePaint = new Paint();
            mSnowflakePaint.setColor(Color.WHITE);
            mSnowflakePaint.setStrokeWidth(STROKE_REST_WIDTH);
            mSnowflakePaint.setAntiAlias(true);
            mSnowflakePaint.setStrokeCap(Paint.Cap.ROUND);
            mSnowflakePaint.setStyle(Paint.Style.STROKE);

            updateColors();

            mCalendar = Calendar.getInstance();
            mDate = new Date();
            initFormats();
        }

        private void updateColors() {
            mBackgroundPaint.setColor(mInteractiveBackgroundColor);

            if (mBackgroundBitmap != null) {
                // Asynchronous call to generate Palette
                Palette.from(mBackgroundBitmap).generate(
                        new Palette.PaletteAsyncListener() {
                            public void onGenerated(Palette palette) {
                            /*
                             * Sometimes, palette is unable to generate a color palette
                             * so we need to check that we have one.
                             */
                                if (palette != null) {
                                    Log.d("onGenerated", palette.toString());
                                    mWatchHandColor = palette.getVibrantColor(Color.WHITE);
                                    mWatchRestHandColor = palette.getLightVibrantColor(Color.WHITE);
                                    mWatchHandShadowColor = palette.getDarkMutedColor(Color.BLACK);
                                    setWatchHandColor();
                                }
                            }
                        });
            } else {
                float[] hsv = new float[3];
                Color.colorToHSV(mInteractiveBackgroundColor, hsv);
                hsv[1] = 0.2f;

                mWatchHandColor = Color.WHITE;
                mWatchRestHandColor = Color.HSVToColor(hsv);
                mWatchHandShadowColor = Color.BLACK;
                setWatchHandColor();
            }
        }

        private void setWatchHandColor() {
            if (mAmbient) {
                mHandPaint.setColor(Color.WHITE);
                mHourHandPaint.setColor(Color.WHITE);
                mSnowflakePaint.setColor(Color.GRAY);
                mHourHandPaint.setShadowLayer(mHourScaleFactor * SHADOW_RADIUS, 0, 0, Color.BLACK);
                mHandPaint.setShadowLayer(SHADOW_RADIUS, 0, 0, Color.BLACK);
            } else {
                mHandPaint.setColor(mWatchHandColor);
                mHourHandPaint.setColor(mWatchHandColor);
                mSnowflakePaint.setColor(mWatchRestHandColor);
                mHourHandPaint.setShadowLayer(mHourScaleFactor * SHADOW_RADIUS, 0, 0, mWatchHandShadowColor);
                mHandPaint.setShadowLayer(SHADOW_RADIUS, 0, 0, mWatchHandShadowColor);
            }
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int defaultInteractiveColor) {
            return createTextPaint(defaultInteractiveColor, NORMAL_TYPEFACE);
        }

        private Paint createTextPaint(int defaultInteractiveColor, Typeface typeface) {
            Paint paint = new Paint();
            paint.setColor(defaultInteractiveColor);
            paint.setTypeface(typeface);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onVisibilityChanged: " + visible);
            }
            super.onVisibilityChanged(visible);

            if (visible) {
                mGoogleApiClient.connect();
//                mStepsGoogleApiClient.connect();

                registerReceiver();

                // Update time zone and date formats, in case they changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                initFormats();
            } else {
                unregisterReceiver();

                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }

//                if (mStepsGoogleApiClient != null && mStepsGoogleApiClient.isConnected()) {
//                    mStepsGoogleApiClient.disconnect();
//                }
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void initFormats() {
            mDayOfWeekFormat = new SimpleDateFormat("EEEE", Locale.getDefault());
            mDayOfWeekFormat.setCalendar(mCalendar);
            mDateFormat = DateFormat.getDateFormat(SnowWatchFaceService.this);
            mDateFormat.setCalendar(mCalendar);
        }

        private void registerReceiver() {
            if (mRegisteredReceiver) {
                return;
            }
            mRegisteredReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            filter.addAction(Intent.ACTION_LOCALE_CHANGED);
            SnowWatchFaceService.this.registerReceiver(mReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredReceiver) {
                return;
            }
            mRegisteredReceiver = false;
            SnowWatchFaceService.this.unregisterReceiver(mReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onApplyWindowInsets: " + (insets.isRound() ? "round" : "square"));
            }
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SnowWatchFaceService.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);
            float amPmSize = resources.getDimension(isRound
                    ? R.dimen.digital_am_pm_size_round : R.dimen.digital_am_pm_size);

            mDatePaint.setTextSize(resources.getDimension(R.dimen.digital_date_text_size));
            mHourPaint.setTextSize(textSize);
            mMinutePaint.setTextSize(textSize);
            mSecondPaint.setTextSize(textSize);
            mAmPmPaint.setTextSize(amPmSize);
            mColonPaint.setTextSize(textSize);

            mColonWidth = mColonPaint.measureText(COLON_STRING);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);

            boolean burnInProtection = properties.getBoolean(PROPERTY_BURN_IN_PROTECTION, false);
            mHourPaint.setTypeface(burnInProtection ? NORMAL_TYPEFACE : BOLD_TYPEFACE);

            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
            mBurnInProtection = properties.getBoolean(PROPERTY_BURN_IN_PROTECTION, false);

            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onPropertiesChanged: burn-in protection = " + burnInProtection
                        + ", low-bit ambient = " + mLowBitAmbient);
            }
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onTimeTick: ambient = " + isInAmbientMode());
            }

//            getTotalSteps();
//
//            invalidate();
        }

//        private void getTotalSteps() {
//            if (Log.isLoggable(TAG, Log.DEBUG)) {
//                Log.d(TAG, "getTotalSteps()");
//            }
//
//            if ((mStepsGoogleApiClient != null)
//                    && (mStepsGoogleApiClient.isConnected())
//                    && (!mStepsRequested)) {
//
//                mStepsRequested = true;
//
//                PendingResult<DailyTotalResult> stepsResult =
//                        Fitness.HistoryApi.readDailyTotal(
//                                mStepsGoogleApiClient,
//                                DataType.TYPE_STEP_COUNT_DELTA);
//
//                stepsResult.setResultCallback(this);
//            }
//        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onAmbientModeChanged: " + inAmbientMode);
            }
            adjustPaintColorToCurrentMode(mBackgroundPaint, mInteractiveBackgroundColor,
                    SnowWatchFaceUtil.COLOR_VALUE_DEFAULT_AND_AMBIENT_BACKGROUND);
            adjustPaintColorToCurrentMode(mHourPaint, mInteractiveHourDigitsColor,
                    SnowWatchFaceUtil.COLOR_VALUE_DEFAULT_AND_AMBIENT_HOUR_DIGITS);
            adjustPaintColorToCurrentMode(mMinutePaint, mInteractiveMinuteDigitsColor,
                    SnowWatchFaceUtil.COLOR_VALUE_DEFAULT_AND_AMBIENT_MINUTE_DIGITS);
            // Actually, the seconds are not rendered in the ambient mode, so we could pass just any
            // value as ambientColor here.
            adjustPaintColorToCurrentMode(mSecondPaint, mInteractiveSecondDigitsColor,
                    SnowWatchFaceUtil.COLOR_VALUE_DEFAULT_AND_AMBIENT_SECOND_DIGITS);

            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient || mBurnInProtection) {
                    mHandPaint.setAntiAlias(!inAmbientMode);
                }
                setWatchHandColor();
                invalidate();
            }

            if (mLowBitAmbient) {
                boolean antiAlias = !inAmbientMode;
                mDatePaint.setAntiAlias(antiAlias);
                mHourPaint.setAntiAlias(antiAlias);
                mMinutePaint.setAntiAlias(antiAlias);
                mSecondPaint.setAntiAlias(antiAlias);
                mAmPmPaint.setAntiAlias(antiAlias);
                mColonPaint.setAntiAlias(antiAlias);
            }
            invalidate();

            // Whether the timer should be running depends on whether we're in ambient mode (as well
            // as whether we're visible), so we may need to start or stop the timer.
            updateTimer();
        }

        private void adjustPaintColorToCurrentMode(Paint paint, int interactiveColor,
                                                   int ambientColor) {
            paint.setColor(isInAmbientMode() ? ambientColor : interactiveColor);
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            mWidth = width;
            mHeight = height;
            /*
             * Find the coordinates of the center point on the screen.
             * Ignore the window insets so that, on round watches
             * with a "chin", the watch face is centered on the entire screen,
             * not just the usable portion.
             */
            mCenterX = mWidth / 2f;
            mCenterY = mHeight / 2f;
            /*
             * Calculate the lengths of the watch hands and store them in member variables.
             */
            mHourHandRadius = mCenterX * 0.5f;
            mMinuteHandLength = mCenterX * 0.7f;
            mSecondHandLength = mCenterX * 0.9f;

            if (mBackgroundBitmap != null) {
                mScale = ((float) width) / (float) mBackgroundBitmap.getWidth();

                mBackgroundBitmap = Bitmap.createScaledBitmap(mBackgroundBitmap,
                        (int) (mBackgroundBitmap.getWidth() * mScale),
                        (int) (mBackgroundBitmap.getHeight() * mScale), true);

                if (!mBurnInProtection || !mLowBitAmbient) {
                    initGrayBackgroundBitmap();
                }
            }
        }

        private void initGrayBackgroundBitmap() {
            mGrayBackgroundBitmap = Bitmap.createBitmap(mBackgroundBitmap.getWidth(),
                    mBackgroundBitmap.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(mGrayBackgroundBitmap);
            Paint grayPaint = new Paint();
            ColorMatrix colorMatrix = new ColorMatrix();
            colorMatrix.setSaturation(0);
            ColorMatrixColorFilter filter = new ColorMatrixColorFilter(colorMatrix);
            grayPaint.setColorFilter(filter);
            canvas.drawBitmap(mBackgroundBitmap, 0, 0, grayPaint);
        }

        @Override
        public void onInterruptionFilterChanged(int interruptionFilter) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onInterruptionFilterChanged: " + interruptionFilter);
            }
            super.onInterruptionFilterChanged(interruptionFilter);

            boolean inMuteMode = interruptionFilter == WatchFaceService.INTERRUPTION_FILTER_NONE;
            // We only need to update once a minute in mute mode.
            setInteractiveUpdateRateMs(inMuteMode ? MUTE_UPDATE_RATE_MS : NORMAL_UPDATE_RATE_MS);

            if (mMute != inMuteMode) {
                mMute = inMuteMode;
                int alpha = inMuteMode ? MUTE_ALPHA : NORMAL_ALPHA;
                mDatePaint.setAlpha(alpha);
                mHourPaint.setAlpha(alpha);
                mMinutePaint.setAlpha(alpha);
                mColonPaint.setAlpha(alpha);
                mAmPmPaint.setAlpha(alpha);
                invalidate();
            }
        }

        public void setInteractiveUpdateRateMs(long updateRateMs) {
            if (updateRateMs == mInteractiveUpdateRateMs) {
                return;
            }
            mInteractiveUpdateRateMs = updateRateMs;

            // Stop and restart the timer so the new update rate takes effect immediately.
            if (shouldTimerBeRunning()) {
                updateTimer();
            }
        }

        private void updatePaintIfInteractive(Paint paint, int interactiveColor) {
            if (!isInAmbientMode() && paint != null) {
                paint.setColor(interactiveColor);
            }
        }

        private void setInteractiveBackgroundColor(int color) {
            mInteractiveBackgroundColor = color;
            updatePaintIfInteractive(mBackgroundPaint, color);
            updateColors();
        }

        private void setInteractiveHourDigitsColor(int color) {
            mInteractiveHourDigitsColor = color;
            updatePaintIfInteractive(mHourPaint, color);
        }

        private void setInteractiveMinuteDigitsColor(int color) {
            mInteractiveMinuteDigitsColor = color;
            updatePaintIfInteractive(mMinutePaint, color);
        }

        private void setInteractiveSecondDigitsColor(int color) {
            mInteractiveSecondDigitsColor = color;
            updatePaintIfInteractive(mSecondPaint, color);
        }

        private String formatTwoDigitNumber(int hour) {
            return String.format("%02d", hour);
        }

        private String getAmPmString(int amPm) {
            return amPm == Calendar.AM ? mAmString : mPmString;
        }

        @Override
        public void onPeekCardPositionUpdate(Rect rect) {
            super.onPeekCardPositionUpdate(rect);
            mCardBounds.set(rect);
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);
            mDate.setTime(now);

            // Draw the background.
            drawBackground(canvas);

            // Draw tickmarks
            drawTicks(canvas);

            // Draw digital time
//            drawDigital(canvas);

            // Draw snowflake
            drawMorphSnowflake(canvas);

            // Draw snowflake watch hands
//            drawSnowflakeHands(canvas);

            // Draw analog time
            drawAnalogHands(canvas);

            // Draw center text
            SimpleDateFormat timeFormat = new SimpleDateFormat("h:mm",java.util.Locale.getDefault());
            String time = timeFormat.format(mCalendar.getTime());
            drawCenterText(canvas, time);
//            drawCenterText(canvas,Integer.toString(mCount));

            // Draw background for peek cards
//            drawCardBackground(canvas);
        }

        private void drawBackground(Canvas canvas) {
            if (mAmbient && (mLowBitAmbient || mBurnInProtection)) {
                canvas.drawColor(Color.BLACK);
            } else if (mBackgroundBitmap == null) {
                canvas.drawColor(mInteractiveBackgroundColor);
            } else if (mAmbient) {
                canvas.drawBitmap(mGrayBackgroundBitmap, 0, 0, mBackgroundPaint);
            } else {
                canvas.drawBitmap(mBackgroundBitmap, 0, 0, mBackgroundPaint);
            }
        }

        private void drawTicks(Canvas canvas) {
            /*
             * Draw ticks. Usually you will want to bake this directly into the photo, but in
             * cases where you want to allow users to select their own photos, this dynamically
             * creates them on top of the photo.
             */
            float innerTickRadius = mCenterX - 10;
            float outerTickRadius = mCenterX;
            for (int tickIndex = 0; tickIndex < 12; tickIndex++) {
                float tickRot = (float) (tickIndex * Math.PI * 2 / 12);
                float innerX = (float) Math.sin(tickRot) * innerTickRadius;
                float innerY = (float) -Math.cos(tickRot) * innerTickRadius;
                float outerX = (float) Math.sin(tickRot) * outerTickRadius;
                float outerY = (float) -Math.cos(tickRot) * outerTickRadius;
                canvas.drawLine(mCenterX + innerX, mCenterY + innerY,
                        mCenterX + outerX, mCenterY + outerY, mSnowflakePaint);
            }
        }

        private void drawCardBackground(Canvas canvas) {
            if (mAmbient) {
                canvas.drawRect(mCardBounds, mBackgroundPaint);
            }
        }

        private void drawCenterText(Canvas canvas, String text) {
            // Test draw text
            Paint textPaint = new Paint(mHandPaint);
            float textSize = 20f;
            textPaint.setTextSize(textSize);
            textPaint.setStrokeWidth(STROKE_WIDTH / 4);
            textPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(text, mCenterX, mCenterY+textSize/3, textPaint);
        }

        private void drawMorphSnowflake(Canvas canvas) {
             /*
             * These calculations reflect the rotation in degrees per unit of time, e.g.,
             * 360 / 60 = 6 and 360 / 12 = 30.
             */
            final float seconds =
                    (mCalendar.get(Calendar.SECOND) + mCalendar.get(Calendar.MILLISECOND) / 1000f);
            final float minutes = mCalendar.get(Calendar.MINUTE);
            final float hours = mCalendar.get(Calendar.HOUR_OF_DAY);
            final int time = (int) ((hours * 60 * 60) + (minutes * 60) + seconds);
            final int timeCount = time / 14;
            final int count = timeCount;
            mCount = count;

            final float hourHandOffset = mCalendar.get(Calendar.MINUTE) / 2f;
            final float hoursRotation = (mCalendar.get(Calendar.HOUR) * 30) + hourHandOffset;

            // save the canvas state before we begin to rotate it
            canvas.save();

            // Align with the hour hand
            canvas.rotate(hoursRotation, mCenterX, mCenterY);

            float maxInnerRadius = mHourHandRadius;
            float maxInnerStubLength = mHourHandRadius / 10;

            float[] threshold = new float[]{-500, 1000, 2000, 3000, 5000, 8000, 10000};

            if (count < threshold[1]) {
                // Draw 6 pointed snowflake that grows as count increases
                drawSnowflake(
                        canvas,
                        6,
                        CENTER_GAP_AND_CIRCLE_RADIUS,
                        ((count - threshold[0]) / (threshold[1] - threshold[0])) * maxInnerRadius,
                        2,
                        ((count - threshold[0]) / (threshold[1] - threshold[0])) * maxInnerStubLength,
                        0f);
            } else if (count < threshold[2]) {
                // Draw 6 pointed snowflake that stays the same
                drawSnowflake(
                        canvas,
                        6,
                        CENTER_GAP_AND_CIRCLE_RADIUS,
                        maxInnerRadius,
                        2,
                        maxInnerStubLength,
                        0f);

                // Draw 6 pointed snowflake that grows as count increases
                drawSnowflake(
                        canvas,
                        6,
                        CENTER_GAP_AND_CIRCLE_RADIUS,
                        ((count - threshold[1]) / (threshold[2] - threshold[1])) * maxInnerRadius*3/2,
                        3,
                        ((count - threshold[1]) / (threshold[2] - threshold[1])) * maxInnerStubLength/2,
                        30f);
            } else if (count < threshold[3]) {
                // Draw 6 pointed snowflake that shrinks to nothing as count increases
                drawSnowflake(
                        canvas,
                        6,
                        CENTER_GAP_AND_CIRCLE_RADIUS,
                        maxInnerRadius - ((count - threshold[2]) / (threshold[3] - threshold[2])) * maxInnerRadius,
                        2,
                        maxInnerStubLength - ((count - threshold[2]) / (threshold[3] - threshold[2])) * maxInnerStubLength,
                        0f);

                // Draw 6 pointed snowflake that rotates left as count increases
                drawSnowflake(
                        canvas,
                        6,
                        CENTER_GAP_AND_CIRCLE_RADIUS,
                        maxInnerRadius*3/2,
                        3,
                        maxInnerStubLength/2,
                        30f - ((count - threshold[2]) / (threshold[3] - threshold[2]))* 15f);

                // Draw 6 pointed snowflake that shrinks and rotates right as count increases
                drawSnowflake(
                        canvas,
                        6,
                        CENTER_GAP_AND_CIRCLE_RADIUS,
                        maxInnerRadius*3/2 - ((count - threshold[2]) / (threshold[3] - threshold[2])) * maxInnerRadius*3/4,
                        3,
                        maxInnerStubLength/2 - ((count - threshold[2]) / (threshold[3] - threshold[2])) * maxInnerStubLength/4,
                        30f + ((count - threshold[2]) / (threshold[3] - threshold[2]))* 15f);
            } else if (count < threshold[4]) {
                // Draw 6 pointed large snowflake that stays the same size
                drawSnowflake(
                        canvas,
                        6,
                        CENTER_GAP_AND_CIRCLE_RADIUS,
                        maxInnerRadius*3/2,
                        3 + count % 3,
                        maxInnerStubLength/2,
                        15f);

                // Draw 6 pointed small snowflake that stays the same size
                drawSnowflake(
                        canvas,
                        6,
                        CENTER_GAP_AND_CIRCLE_RADIUS,
                        maxInnerRadius*3/4,
                        3,
                        maxInnerStubLength/4,
                        45f);

                // Draw mini snowflakes that grow and move outward in the spaces in-between
                drawMiniSnowflake(
                        canvas,
                        6,
                        maxInnerRadius + ((count - threshold[3]) / (threshold[4] - threshold[3])) * maxInnerRadius / 4,
                        8,
                        ((count - threshold[3]) / (threshold[4] - threshold[3])) * maxInnerStubLength,
                        45f);
            } else {
                // Draw 6 pointed large snowflake that stays the same size
                drawSnowflake(
                        canvas,
                        6,
                        CENTER_GAP_AND_CIRCLE_RADIUS,
                        maxInnerRadius * 3 / 2,
                        3 + count % 3,
                        maxInnerStubLength / 2,
                        15f);

                // Draw 6 pointed small snowflake that stays the same size
                drawSnowflake(
                        canvas,
                        6,
                        CENTER_GAP_AND_CIRCLE_RADIUS,
                        maxInnerRadius * 3 / 4,
                        3 - count % 2,
                        maxInnerStubLength / 4,
                        45f);

                // Draw mini snowflakes that grow and move outward in the spaces in-between
                drawMiniSnowflake(
                        canvas,
                        6,
                        maxInnerRadius * 5 / 4,
                        8,
                        maxInnerStubLength + (count % 8) * maxInnerStubLength / 8,
                        45f);
            }

            // restore the canvas' original orientation.
            canvas.restore();

        }

        private void drawSnowflake(Canvas canvas, int points, float innerRadius, float outerRadius, int stubs, float stubLength, float angleOffset) {
            // Rotate to offset position
            canvas.rotate(angleOffset, mCenterX, mCenterY);

            // Prepare the inner snowflake
            for (int i = 0; i < points; i++) {

                float length = outerRadius - innerRadius;

                for (int j = 0; j < stubs; j++) {
                    float yTickStart = mCenterY - innerRadius - (length / (stubs + 1)) * (j + 1);

                    canvas.drawLine(mCenterX,
                            yTickStart,
                            mCenterX - stubLength * (stubs - j),
                            yTickStart - stubLength * (stubs - j),
                            mSnowflakePaint);
                    canvas.drawLine(mCenterX,
                            yTickStart,
                            mCenterX + stubLength * (stubs - j),
                            yTickStart - stubLength * (stubs - j),
                            mSnowflakePaint);
                }

                canvas.drawLine(
                        mCenterX,
                        mCenterY - innerRadius,
                        mCenterX,
                        mCenterY - outerRadius,
                        mSnowflakePaint);

                canvas.rotate(360 / points, mCenterX, mCenterY);
            }

            // Rotate back to starting position
            canvas.rotate(-angleOffset, mCenterX, mCenterY);
        }

        private void drawMiniSnowflake(Canvas canvas, int points, float radius, int stubs, float stubLength, float angleOffset) {
            // Rotate to offset position
            canvas.rotate(angleOffset, mCenterX, mCenterY);

            // Prepare the inner snowflake
            for (int i = 0; i < points; i++) {

                for (int j = 0; j < stubs; j++) {

                    float yCenter = mCenterY - radius;

                    canvas.drawLine(mCenterX,
                            yCenter,
                            mCenterX,
                            yCenter - stubLength,
                            mSnowflakePaint);
                    canvas.rotate(360/stubs,mCenterX,yCenter);
                }
                canvas.rotate(360 / points, mCenterX, mCenterY);
            }

            // Rotate back to starting position
            canvas.rotate(-angleOffset, mCenterX, mCenterY);
        }

        private void drawSnowflakeHands(Canvas canvas) {
            /*
             * These calculations reflect the rotation in degrees per unit of time, e.g.,
             * 360 / 60 = 6 and 360 / 12 = 30.
             */
            final float seconds =
                    (mCalendar.get(Calendar.SECOND) + mCalendar.get(Calendar.MILLISECOND) / 1000f);
            final float secondsRotation = seconds * 6f;

            final float minutesRotation = mCalendar.get(Calendar.MINUTE) * 6f;

            final float hourHandOffset = mCalendar.get(Calendar.MINUTE) / 2f;
            final float hoursRotation = (mCalendar.get(Calendar.HOUR) * 30) + hourHandOffset;

            // save the canvas state before we begin to rotate it
            canvas.save();

            /* Prepare the hour snowflake*/
            canvas.rotate(hoursRotation, mCenterX, mCenterY);

            int hourPoints = 6;
            int hourStubs = 2;
            float hourStubLength = 10f;

            for (int i = 0; i < hourPoints; i++) {

                for (int j = 0; j < hourStubs; j++) {
                    float yTickStart = mCenterY - (mHourHandRadius / (hourStubs + 1)) * (j + 1);

                    canvas.drawLine(mCenterX,
                            yTickStart,
                            mCenterX - hourStubLength * (hourStubs - j),
                            yTickStart - hourStubLength * (hourStubs - j),
                            mSnowflakePaint);
                    canvas.drawLine(mCenterX,
                            yTickStart,
                            mCenterX + hourStubLength * (hourStubs - j),
                            yTickStart - hourStubLength * (hourStubs - j),
                            mSnowflakePaint);
                }

                canvas.drawLine(
                        mCenterX,
                        mCenterY - CENTER_GAP_AND_CIRCLE_RADIUS,
                        mCenterX,
                        mCenterY - mHourHandRadius,
                        mSnowflakePaint);

                canvas.rotate(360 / hourPoints, mCenterX, mCenterY);
            }


            /* Prepare the minute snowflake */
            canvas.rotate(minutesRotation - hoursRotation, mCenterX, mCenterY);

            int minutePoints = 12;
            int minuteStubs = 2;
            float minutePadding = 6;
            float minuteStubLength = hourStubLength;

            float minuteStartY = mCenterY - mHourHandRadius - minutePadding;
            float minuteEndY = mCenterY - mMinuteHandLength;
            float minuteLength = minuteStartY - minuteEndY;

            for (int i = 0; i < minutePoints; i++) {

                for (int j = 0; j < minuteStubs; j++) {
                    float yTickStart = minuteStartY - (minuteLength / minuteStubs) * j;

                    canvas.drawLine(mCenterX,
                            yTickStart,
                            mCenterX - minuteStubLength * (minuteStubs - j),
                            yTickStart - minuteStubLength * (minuteStubs - j),
                            mSnowflakePaint);
                    canvas.drawLine(mCenterX,
                            yTickStart,
                            mCenterX + minuteStubLength * (minuteStubs - j),
                            yTickStart - minuteStubLength * (minuteStubs - j),
                            mSnowflakePaint);
                }

                canvas.drawLine(
                        mCenterX,
                        minuteStartY,
                        mCenterX,
                        minuteEndY,
                        mSnowflakePaint);

                canvas.rotate(360 / minutePoints, mCenterX, mCenterY);
            }

            canvas.drawCircle(mCenterX, mCenterY, HAND_END_CAP_RADIUS, mHandPaint);

            // restore the canvas' original orientation.
            canvas.restore();
        }

        private void drawAnalogHands(Canvas canvas) {
            /*
             * These calculations reflect the rotation in degrees per unit of time, e.g.,
             * 360 / 60 = 6 and 360 / 12 = 30.
             */
            final float seconds =
                    (mCalendar.get(Calendar.SECOND) + mCalendar.get(Calendar.MILLISECOND) / 1000f);
            final float secondsRotation = seconds * 6f;

            final float minutesRotation = mCalendar.get(Calendar.MINUTE) * 6f;

            final float hourHandOffset = mCalendar.get(Calendar.MINUTE) / 2f;
            final float hoursRotation = (mCalendar.get(Calendar.HOUR) * 30) + hourHandOffset;

            // save the canvas state before we begin to rotate it
            canvas.save();

            /* Prepare the hour snowflake*/
            canvas.rotate(hoursRotation, mCenterX, mCenterY);

            canvas.drawLine(
                mCenterX,
                mCenterY - CENTER_GAP_AND_CIRCLE_RADIUS,
                mCenterX,
                mCenterY - mHourHandRadius,
                mHourHandPaint);

            /* Prepare the minute snowflake */
            canvas.rotate(minutesRotation - hoursRotation, mCenterX, mCenterY);

            float minuteEndY = mCenterY - mMinuteHandLength;

            canvas.drawLine(
                    mCenterX,
                    mCenterY - CENTER_GAP_AND_CIRCLE_RADIUS,
                    mCenterX,
                    minuteEndY,
                    mHandPaint);

            /*
             * Make sure the "seconds" hand is drawn only when we are in interactive mode.
             * Otherwise we only update the watch face once a minute.
             */
            if (!mAmbient) {
                canvas.rotate(secondsRotation - minutesRotation, mCenterX, mCenterY);
                canvas.drawLine(mCenterX,
                        mCenterY - CENTER_GAP_AND_CIRCLE_RADIUS,
                        mCenterX,
                        mCenterY - mSecondHandLength,
                        mHandPaint);
            }

            // Draw center circle
            canvas.drawCircle(mCenterX, mCenterY, CENTER_GAP_AND_CIRCLE_RADIUS, mHandPaint);

            // restore the canvas' original orientation.
            canvas.restore();
        }

        /**
         * Draws the digital time on the canvas
         * @param canvas
         */
        private void drawDigital(Canvas canvas) {
            boolean is24Hour = DateFormat.is24HourFormat(SnowWatchFaceService.this);

            // Show colons for the first half of each second so the colons blink on when the time
            // updates.
            mShouldDrawColons = (System.currentTimeMillis() % 1000) < 500;


            // Draw the hours.
            float x = mXOffset;
            String hourString;
            if (is24Hour) {
                hourString = formatTwoDigitNumber(mCalendar.get(Calendar.HOUR_OF_DAY));
            } else {
                int hour = mCalendar.get(Calendar.HOUR);
                if (hour == 0) {
                    hour = 12;
                }
                hourString = String.valueOf(hour);
            }
            canvas.drawText(hourString, x, mYOffset, mHourPaint);
            x += mHourPaint.measureText(hourString);

            // In ambient and mute modes, always draw the first colon. Otherwise, draw the
            // first colon for the first half of each second.
            if (isInAmbientMode() || mMute || mShouldDrawColons) {
                canvas.drawText(COLON_STRING, x, mYOffset, mColonPaint);
            }
            x += mColonWidth;

            // Draw the minutes.
            String minuteString = formatTwoDigitNumber(mCalendar.get(Calendar.MINUTE));
            canvas.drawText(minuteString, x, mYOffset, mMinutePaint);
            x += mMinutePaint.measureText(minuteString);

            // In unmuted interactive mode, draw a second blinking colon followed by the seconds.
            // Otherwise, if we're in 12-hour mode, draw AM/PM
            if (!isInAmbientMode() && !mMute) {
                if (mShouldDrawColons) {
                    canvas.drawText(COLON_STRING, x, mYOffset, mColonPaint);
                }
                x += mColonWidth;
                canvas.drawText(formatTwoDigitNumber(
                        mCalendar.get(Calendar.SECOND)), x, mYOffset, mSecondPaint);
            } else if (!is24Hour) {
                x += mColonWidth;
                canvas.drawText(getAmPmString(
                        mCalendar.get(Calendar.AM_PM)), x, mYOffset, mAmPmPaint);
            }

            // Only render the day of week and date if there is no peek card, so they do not bleed
            // into each other in ambient mode.
            if (getPeekCardPosition().isEmpty()) {
                // Day of week
                canvas.drawText(
                        mDayOfWeekFormat.format(mDate),
                        mXOffset, mYOffset + mLineHeight, mDatePaint);
                // Date
                canvas.drawText(
                        mDateFormat.format(mDate),
                        mXOffset, mYOffset + mLineHeight * 2, mDatePaint);
            }
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "updateTimer");
            }
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

        private void updateConfigDataItemAndUiOnStartup() {
            SnowWatchFaceUtil.fetchConfigDataMap(mGoogleApiClient,
                    new SnowWatchFaceUtil.FetchConfigDataMapCallback() {
                        @Override
                        public void onConfigDataMapFetched(DataMap startupConfig) {
                            // If the DataItem hasn't been created yet or some keys are missing,
                            // use the default values.
                            setDefaultValuesForMissingConfigKeys(startupConfig);
                            SnowWatchFaceUtil.putConfigDataItem(mGoogleApiClient, startupConfig);

                            updateUiForConfigDataMap(startupConfig);
                        }
                    }
            );
        }

        private void setDefaultValuesForMissingConfigKeys(DataMap config) {
            addIntKeyIfMissing(config, SnowWatchFaceUtil.KEY_BACKGROUND_COLOR,
                    SnowWatchFaceUtil.COLOR_VALUE_DEFAULT_AND_AMBIENT_BACKGROUND);
            addIntKeyIfMissing(config, SnowWatchFaceUtil.KEY_HOURS_COLOR,
                    SnowWatchFaceUtil.COLOR_VALUE_DEFAULT_AND_AMBIENT_HOUR_DIGITS);
            addIntKeyIfMissing(config, SnowWatchFaceUtil.KEY_MINUTES_COLOR,
                    SnowWatchFaceUtil.COLOR_VALUE_DEFAULT_AND_AMBIENT_MINUTE_DIGITS);
            addIntKeyIfMissing(config, SnowWatchFaceUtil.KEY_SECONDS_COLOR,
                    SnowWatchFaceUtil.COLOR_VALUE_DEFAULT_AND_AMBIENT_SECOND_DIGITS);
        }

        private void addIntKeyIfMissing(DataMap config, String key, int color) {
            if (!config.containsKey(key)) {
                config.putInt(key, color);
            }
        }

        @Override // DataApi.DataListener
        public void onDataChanged(DataEventBuffer dataEvents) {
            for (DataEvent dataEvent : dataEvents) {
                if (dataEvent.getType() != DataEvent.TYPE_CHANGED) {
                    continue;
                }

                DataItem dataItem = dataEvent.getDataItem();
                if (!dataItem.getUri().getPath().equals(
                        SnowWatchFaceUtil.PATH_WITH_FEATURE)) {
                    continue;
                }

                DataMapItem dataMapItem = DataMapItem.fromDataItem(dataItem);
                DataMap config = dataMapItem.getDataMap();
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Config DataItem updated:" + config);
                }
                updateUiForConfigDataMap(config);
            }
        }

        private void updateUiForConfigDataMap(final DataMap config) {
            boolean uiUpdated = false;
            for (String configKey : config.keySet()) {
                if (!config.containsKey(configKey)) {
                    continue;
                }
                int color = config.getInt(configKey);
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Found watch face config key: " + configKey + " -> "
                            + Integer.toHexString(color));
                }
                if (updateUiForKey(configKey, color)) {
                    uiUpdated = true;
                }
            }
            if (uiUpdated) {
                invalidate();
            }
        }

        /**
         * Updates the color of a UI item according to the given {@code configKey}. Does nothing if
         * {@code configKey} isn't recognized.
         *
         * @return whether UI has been updated
         */
        private boolean updateUiForKey(String configKey, int color) {
            if (configKey.equals(SnowWatchFaceUtil.KEY_BACKGROUND_COLOR)) {
                setInteractiveBackgroundColor(color);
            } else if (configKey.equals(SnowWatchFaceUtil.KEY_HOURS_COLOR)) {
                setInteractiveHourDigitsColor(color);
            } else if (configKey.equals(SnowWatchFaceUtil.KEY_MINUTES_COLOR)) {
                setInteractiveMinuteDigitsColor(color);
            } else if (configKey.equals(SnowWatchFaceUtil.KEY_SECONDS_COLOR)) {
                setInteractiveSecondDigitsColor(color);
            } else {
                Log.w(TAG, "Ignoring unknown config key: " + configKey);
                return false;
            }
            return true;
        }

        @Override  // GoogleApiClient.ConnectionCallbacks
        public void onConnected(Bundle connectionHint) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onConnected: " + connectionHint);
            }
            Wearable.DataApi.addListener(mGoogleApiClient, Engine.this);
            updateConfigDataItemAndUiOnStartup();

//            if (Log.isLoggable(TAG, Log.DEBUG)) {
//                Log.d(TAG, "mGoogleApiAndFitCallbacks.onConnected: " + connectionHint);
//            }
//            mStepsRequested = false;
//
//            // The subscribe step covers devices that do not have Google Fit installed.
//            subscribeToSteps();
//
//            getTotalSteps();
        }

        /*
         * Subscribes to step count (for phones that don't have Google Fit app).
         */
        private void subscribeToSteps() {
            Fitness.RecordingApi.subscribe(mStepsGoogleApiClient, DataType.TYPE_STEP_COUNT_DELTA)
                    .setResultCallback(new ResultCallback<Status>() {
                        @Override
                        public void onResult(Status status) {
                            if (status.isSuccess()) {
                                if (status.getStatusCode()
                                        == FitnessStatusCodes.SUCCESS_ALREADY_SUBSCRIBED) {
                                    Log.i(TAG, "Existing subscription for activity detected.");
                                } else {
                                    Log.i(TAG, "Successfully subscribed!");
                                }
                            } else {
                                Log.i(TAG, "There was a problem subscribing.");
                            }
                        }
                    });
        }

        @Override  // GoogleApiClient.ConnectionCallbacks
        public void onConnectionSuspended(int cause) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onConnectionSuspended: " + cause);
            }
        }

        @Override  // GoogleApiClient.OnConnectionFailedListener
        public void onConnectionFailed(ConnectionResult result) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onConnectionFailed: " + result);
            }
        }

//        @Override
//        public void onResult(DailyTotalResult dailyTotalResult) {
//            if (Log.isLoggable(TAG, Log.DEBUG)) {
//                Log.d(TAG, "mGoogleApiAndFitCallbacks.onResult(): " + dailyTotalResult);
//            }
//
//            mStepsRequested = false;
//
//            if (dailyTotalResult.getStatus().isSuccess()) {
//
//                List<DataPoint> points = dailyTotalResult.getTotal().getDataPoints();;
//
//                if (!points.isEmpty()) {
//                    mStepsTotal = points.get(0).getValue(Field.FIELD_STEPS).asInt();
//                    Log.d(TAG, "steps updated: " + mStepsTotal);
//                }
//            } else {
//                Log.e(TAG, "onResult() failed! " + dailyTotalResult.getStatus().getStatusMessage());
//            }
//        }
    }
}
