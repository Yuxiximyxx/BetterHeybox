package com.better.heybox.hooks

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.EditText
import com.better.heybox.MainModule
import java.util.Collections
import java.util.WeakHashMap

/**
 * fork 定制：回复输入框与输入法贴合。
 *
 * 原版评论输入框是带左右边距的悬浮圆角卡片，浮在键盘上方，
 * 和输入法面板明显割裂。键盘弹起时找到评论输入框，把它所在的
 * 卡片容器左右/底部边距清零、背景换成同色纯色填充，并给外层
 * 块容器铺同色，让输入区与键盘连成一整块。
 */
class CommentInputDockHook(private val module: MainModule) {

    private val watched: MutableSet<Activity> =
        Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())
    private val lastScan: MutableMap<Activity, Long> =
        Collections.synchronizedMap(WeakHashMap<Activity, Long>())
    private val styled: MutableSet<View> =
        Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    fun install(cl: ClassLoader) {
        try {
            val activityClass = Class.forName("android.app.Activity", false, cl)
            val onResume = activityClass.getDeclaredMethod("onResume")
            module.hook(onResume).intercept { chain ->
                val result = chain.proceed()
                val activity = chain.instanceOrNull as? Activity
                if (activity != null) watch(activity)
                result
            }
            module.logd(Log.INFO, MainModule.TAG, "✔ 评论输入框贴合 Hook 已安装（fork 定制）")
        } catch (t: Throwable) {
            module.logd(Log.ERROR, MainModule.TAG, "✘ 评论输入框贴合 Hook 失败", t)
        }
    }

    private fun watch(activity: Activity) {
        if (!watched.add(activity)) return
        val decor = activity.window?.decorView ?: return
        decor.viewTreeObserver.addOnGlobalLayoutListener(
            ViewTreeObserver.OnGlobalLayoutListener { onLayout(activity, decor) }
        )
    }

    private fun onLayout(activity: Activity, decor: View) {
        try {
            val now = System.currentTimeMillis()
            if (now - (lastScan[activity] ?: 0L) < 700L) return
            val frame = Rect()
            decor.getWindowVisibleDisplayFrame(frame)
            val keyboardHeight = decor.height - frame.bottom
            if (keyboardHeight < decor.height * 0.15) return
            lastScan[activity] = now
            val input = findCommentInput(decor) ?: return
            if (!styled.contains(input)) {
                dock(input)
            }
        } catch (t: Throwable) {
            // 贴合失败不影响输入，静默
        }
    }

    private fun findCommentInput(root: View): EditText? {
        val stack = ArrayDeque<View>()
        stack.addLast(root)
        var guard = 0
        while (stack.isNotEmpty() && guard++ < 800) {
            val v = stack.removeLast()
            if (v is EditText) {
                val hint = v.hint?.toString().orEmpty()
                if (hint.contains("来说点什么")) return v
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) stack.addLast(v.getChildAt(i))
            }
        }
        return null
    }

    private fun dock(input: EditText) {
        // v0.10.1 改法：v0.10.0 清边距把输入框内部布局顶坏了（文字贴边
        // 裁切），因为小黑盒的输入框靠容器边距留白。这里不动任何布局
        // 参数，只给卡片外层（面板根，限非全屏高度）铺上卡片同色背景，
        // 让卡片沉进整块面板，视觉上与键盘连成一体。
        var card: View? = null
        var current: View? = input
        var depth = 0
        while (current != null && depth++ < 8) {
            val parent = current.parent as? View ?: break
            if (parent.background != null) {
                card = parent
                break
            }
            current = parent
        }
        val target = card ?: (input.parent as? View) ?: return
        val color = backgroundColor(target.background, target)
        val rootHeight = input.rootView?.height ?: Int.MAX_VALUE
        var level: View? = target.parent as? View
        var painted = 0
        while (level != null && painted < 2) {
            if (level.height > rootHeight * 0.5) break
            level.background = ColorDrawable(color)
            level = level.parent as? View
            painted++
        }
        styled.add(input)
        module.logd(Log.INFO, MainModule.TAG, "评论输入框已贴合（fork 定制）")
    }

    private fun backgroundColor(drawable: Drawable?, anchor: View): Int {
        if (drawable is ColorDrawable) return drawable.color
        val night = (anchor.context.resources.configuration.uiMode
                and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return if (night) Color.rgb(0x24, 0x24, 0x26) else Color.WHITE
    }
}
