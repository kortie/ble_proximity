package dev.proximity.sample

import android.app.Activity
import android.view.View
import android.view.Window
import android.view.WindowInsets

/**
 * targetSdk 35+는 edge-to-edge가 강제라 내용이 상태 바·제목 바 밑으로 들어간다 (S11 실기기에서 발견: 버튼이 가려지고 탭이 제목 바에 맞음).
 * 제목 바를 없애고 시스템 바만큼 안쪽 여백을 준다. setContentView 전에 [noTitle], 뒤에 [padForSystemBars].
 */
fun Activity.noTitle() {
    requestWindowFeature(Window.FEATURE_NO_TITLE)
}

fun View.padForSystemBars(extra: Int = 0) {
    setOnApplyWindowInsetsListener { v, insets ->
        val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        v.setPadding(bars.left + extra, bars.top + extra, bars.right + extra, bars.bottom + extra)
        insets
    }
    requestApplyInsets()
}
