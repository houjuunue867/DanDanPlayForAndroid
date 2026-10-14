package com.xyoye.player.controller.base

import android.content.Context
import android.os.SystemClock
import android.util.AttributeSet
import android.view.KeyEvent
import com.xyoye.common_component.utils.log.AppLog
import com.xyoye.player.controller.video.InterGestureView
import com.xyoye.player.utils.MessageTime

/**
 * Created by xyoye on 2021/5/30.
 */

abstract class TvVideoController(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : BaseVideoController(context, attrs, defStyleAttr) {

    private companion object {
        private const val TAG = "TvVideoController"

        //连续按键的加速倍率，按顺序递增
        private val SEEK_ACCELERATE_MULTIPLIERS = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 6.0, 8.0)

        //超过该间隔未按键，加速倍数重新计算
        private const val SEEK_ACCELERATE_RESET_MS = 2000L

        //长按（按键自动重复）时，两次跳转的最小间隔，避免疯狂拖动进度
        private const val SEEK_REPEAT_INTERVAL_MS = 300L

        //单次跳转最多占总时长的比例，避免短视频一下跳完
        private const val SEEK_MAX_DURATION_RATIO = 0.2
    }

    //连续按键次数，用于计算加速倍数
    private var mSeekAccelerateCount = 0

    //上一次按键时间
    private var mLastSeekKeyTime = 0L

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isPopupMode()) {
            return false
        }
        val intercept = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER -> onActionCenter()
            KeyEvent.KEYCODE_DPAD_UP -> onActionUp()
            KeyEvent.KEYCODE_DPAD_DOWN -> onActionDown()
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> onActionLeft(event)
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> onActionRight(event)
            else -> null
        }
        return if (intercept == null) {
            false
        } else if (intercept) {
            true
        } else {
            mControlWrapper.onKeyDown(keyCode, event)
        }
    }

    private fun onActionCenter(): Boolean {
        if (isLocked()) {
            showController(true)
            return true
        }
        if (mControlWrapper.isSettingViewShowing()) {
            return false
        }
        if (isControllerShowing()) {
            showController(true)
            return false
        }
        togglePlay()
        return true
    }

    private fun onActionUp(): Boolean {
        if (mControlWrapper.isSettingViewShowing()) {
            return false
        }
        showController(true)
        return false
    }

    private fun onActionDown(): Boolean {
        if (mControlWrapper.isSettingViewShowing()) {
            return false
        }
        showController(true)
        return false
    }

    private fun onActionLeft(event: KeyEvent?): Boolean {
        if (mControlWrapper.isSettingViewShowing()) {
            return false
        }
        if (isLocked() || isControllerShowing()) {
            showController(true)
            return false
        }
        changePosition(forward = false, event = event)
        return true
    }

    private fun onActionRight(event: KeyEvent?): Boolean {
        if (mControlWrapper.isSettingViewShowing()) {
            return false
        }
        if (isLocked() || isControllerShowing()) {
            showController(true)
            return false
        }
        changePosition(forward = true, event = event)
        return true
    }

    /**
     * 快进/快退。
     *
     * 跳转步长是动态的：
     * 1. 基础步长随视频总时长变化（短片 5 秒、番剧 10 秒、长片 20 秒、超长片 60 秒）；
     * 2. 短时间内连续按键会逐个加速（×1、×2、×3、×4、×6、×8），停止按键 2 秒后重新计算；
     * 3. 单次跳转不超过视频总时长的 20%，避免短视频一下跳完；
     * 4. 长按（按键自动重复）时限流，按基础步长平滑快进。
     */
    private fun changePosition(forward: Boolean, event: KeyEvent?) {
        val duration = mControlWrapper.getDuration()
        val currentPosition = mControlWrapper.getCurrentPosition()
        val now = SystemClock.elapsedRealtime()
        val isRepeat = (event?.repeatCount ?: 0) > 0

        val step: Long
        if (isRepeat) {
            //长按时限流，且不参与加速，避免进度疯狂跳转
            if (now - mLastSeekKeyTime < SEEK_REPEAT_INTERVAL_MS) {
                return
            }
            mLastSeekKeyTime = now
            step = baseSeekStep(duration)
        } else {
            step = accelerateSeekStep(now, duration)
        }

        val maxPosition = if (duration > 0) duration else Long.MAX_VALUE
        val newPosition = (if (forward) currentPosition + step else currentPosition - step)
            .coerceIn(0L, maxPosition)

        if (newPosition == currentPosition) {
            mControlWrapper.showMessage(
                if (forward) "已到视频结尾" else "已到视频开头",
                MessageTime.SHOT
            )
            return
        }

        for (entry in mControlComponents.entries) {
            val view = entry.key
            if (view is InterGestureView) {
                view.onStartSlide()
                view.onPositionChange(newPosition, currentPosition, duration)
                view.onStopSlide()
            }
        }
        mControlWrapper.seekTo(newPosition)
        mControlWrapper.showMessage(
            "${if (forward) "快进" else "快退"} ${formatSeekStep(step)}",
            MessageTime.SHOT
        )
        AppLog.i(
            TAG,
            "遥控跳转: ${if (forward) "快进" else "快退"} $step ms, " +
                "位置 $currentPosition -> $newPosition, 时长 $duration"
        )
    }

    /**
     * 计算连续按键时的加速步长
     */
    private fun accelerateSeekStep(currentTime: Long, duration: Long): Long {
        if (currentTime - mLastSeekKeyTime > SEEK_ACCELERATE_RESET_MS) {
            mSeekAccelerateCount = 0
        }
        mLastSeekKeyTime = currentTime

        val baseStep = baseSeekStep(duration)
        val maxIndex = SEEK_ACCELERATE_MULTIPLIERS.size - 1
        val multiplier = SEEK_ACCELERATE_MULTIPLIERS[mSeekAccelerateCount.coerceAtMost(maxIndex)]
        if (mSeekAccelerateCount < maxIndex) {
            mSeekAccelerateCount++
        }

        var step = (baseStep * multiplier).toLong()
        if (duration > 0) {
            val maxStep = (duration * SEEK_MAX_DURATION_RATIO).toLong()
            step = step.coerceAtMost(maxStep.coerceAtLeast(baseStep))
        }
        return step
    }

    /**
     * 根据视频总时长计算基础步长
     */
    private fun baseSeekStep(duration: Long): Long {
        return when {
            //时长未知（部分流媒体）时使用默认步长
            duration <= 0 -> 10_000L
            duration < 5 * 60_000L -> 5_000L
            duration < 20 * 60_000L -> 10_000L
            duration < 60 * 60_000L -> 20_000L
            duration < 120 * 60_000L -> 30_000L
            else -> 60_000L
        }
    }

    /**
     * 步长文案，如：30秒、1分钟、1分30秒
     */
    private fun formatSeekStep(stepMs: Long): String {
        val seconds = stepMs / 1000
        return when {
            seconds < 60 -> "${seconds}秒"
            seconds % 60 == 0L -> "${seconds / 60}分钟"
            else -> "${seconds / 60}分${seconds % 60}秒"
        }
    }
}
