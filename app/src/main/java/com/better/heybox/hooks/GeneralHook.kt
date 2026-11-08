package com.better.heybox.hooks

import android.app.Activity
import android.app.Application
import android.content.pm.PackageInfo
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import com.better.heybox.App
import com.better.heybox.ForegroundTracker
import com.better.heybox.LogRecorder
import com.better.heybox.MainModule
import com.better.heybox.liquidglass.LiquidGlassInstaller
import com.better.heybox.liquidglass.WindowImmersiveController
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

class GeneralHook(private val module: MainModule) {

    fun install(cl: ClassLoader) {
        hookFullImmersive(cl)
        hookImageViewerDialog(cl)
        hookVersionNotice(cl)
        hookUpdateBlocking(cl)
        hookFakeNotification(cl)
    }

    private fun hookFakeNotification(cl: ClassLoader) {
        try {
            val nm = Class.forName("android.app.NotificationManager", false, cl)
            val m = nm.getDeclaredMethod("areNotificationsEnabled")
            module.hook(m).intercept { chain ->
                if (module.isEnabled(App.KEY_FAKE_NOTIFICATION, false)) {
                    java.lang.Boolean.TRUE
                } else {
                    chain.proceed()
                }
            }
            module.logd(Log.INFO, MainModule.TAG, "✔ 伪装通知权限 Hook 已安装")
        } catch (t: Throwable) {
            module.logd(Log.ERROR, MainModule.TAG, "✘ 伪装通知权限 Hook 失败", t)
        }
    }

    /**
     * fork 定制：导航条沉浸做全界面（v0.10.1 分级）。
     * v0.10.0 对所有页面都做全量沉浸（清零导航条 inset），把搜索页的
     * 布局顶坏了（顶部搜索框消失）。改为分级：类名像图片/媒体查看页的
     * 做全量，其余页面只做轻量沉浸（透明导航条、不动 inset）；
     * 窗口重新获得焦点时再补一次，防止页面自己重置窗口标志。
     */
    private fun hookFullImmersive(cl: ClassLoader) {
        try {
            val activityClass = Class.forName("android.app.Activity", false, cl)
            val onResume = activityClass.getDeclaredMethod("onResume")
            module.hook(onResume).intercept { chain ->
                val result = chain.proceed()
                val activity = chain.instanceOrNull as? Activity
                if (activity != null) applyImmersiveGraded(activity)
                result
            }
            val onFocus = activityClass.getDeclaredMethod(
                "onWindowFocusChanged", Boolean::class.javaPrimitiveType
            )
            module.hook(onFocus).intercept { chain ->
                val result = chain.proceed()
                val activity = chain.instanceOrNull as? Activity
                val hasFocus = chain.arg(0) as? Boolean
                if (activity != null && hasFocus == true) {
                    applyImmersiveGraded(activity)
                }
                result
            }
            module.logd(Log.INFO, MainModule.TAG, "✔ 全界面导航条沉浸 Hook 已安装（fork 定制）")
        } catch (t: Throwable) {
            module.logd(Log.ERROR, MainModule.TAG, "✘ 全界面导航条沉浸 Hook 失败", t)
        }
    }

    private fun applyImmersiveGraded(activity: Activity) {
        if (activity.javaClass.name.startsWith("com.better.heybox")) {
            return
        }
        val name = activity.javaClass.name.lowercase()
        val mediaLike = MEDIA_KEYWORDS.any { name.contains(it) }
        if (mediaLike) {
            LiquidGlassInstaller.applyImmersive(activity)
        } else {
            LiquidGlassInstaller.applyImmersiveLite(activity)
        }
    }

    /**
     * fork 定制：查看图片若是全屏 Dialog（独立 Window），Activity 级
     * 沉浸够不着、底部会留黑条。这里 hook Dialog.show，对全屏且含
     * 大图/翻页容器的 Dialog 单独给它的 Window 做全量沉浸。
     */
    private fun hookImageViewerDialog(cl: ClassLoader) {
        try {
            val dialogClass = Class.forName("android.app.Dialog", false, cl)
            val show = dialogClass.getDeclaredMethod("show")
            module.hook(show).intercept { chain ->
                val result = chain.proceed()
                val dialog = chain.instanceOrNull as? android.app.Dialog
                val window = dialog?.window
                val decor = window?.decorView
                if (window != null && decor != null) {
                    decor.post {
                        if (isMediaViewer(decor)) {
                            WindowImmersiveController.applyToWindow(window)
                        }
                    }
                    decor.postDelayed({
                        if (isMediaViewer(decor)) {
                            WindowImmersiveController.applyToWindow(window)
                        }
                    }, 350L)
                }
                result
            }
            module.logd(Log.INFO, MainModule.TAG, "✔ 图片查看 Dialog 沉浸 Hook 已安装（fork 定制）")
        } catch (t: Throwable) {
            module.logd(Log.ERROR, MainModule.TAG, "✘ 图片查看 Dialog 沉浸 Hook 失败", t)
        }
    }

    private fun isMediaViewer(decor: View): Boolean {
        try {
            val metrics = decor.resources.displayMetrics
            if (decor.width < metrics.widthPixels * 0.85 ||
                decor.height < metrics.heightPixels * 0.85
            ) {
                return false
            }
            var hasMedia = false
            var hasInput = false
            val stack = ArrayDeque<View>()
            stack.addLast(decor)
            var guard = 0
            while (stack.isNotEmpty() && guard++ < 600) {
                val v = stack.removeLast()
                val cn = v.javaClass.name
                if (cn.contains("ViewPager") ||
                    (v is android.widget.ImageView &&
                        v.width >= decor.width * 0.8 &&
                        v.height >= decor.height * 0.5)
                ) {
                    hasMedia = true
                }
                if (v is android.widget.EditText) {
                    hasInput = true
                }
                if (v is android.view.ViewGroup) {
                    for (i in 0 until v.childCount) stack.addLast(v.getChildAt(i))
                }
            }
            return hasMedia && !hasInput
        } catch (t: Throwable) {
            return false
        }
    }

    private fun hookVersionNotice(cl: ClassLoader) {
        try {
            val baseActivity = Class.forName("com.max.hbcommon.base.BaseActivity", false, cl)
            val onResume = baseActivity.getDeclaredMethod("onResume")
            module.hook(onResume).intercept { chain ->
                val result = chain.proceed()
                val activity = chain.instanceOrNull as? Activity
                if (activity != null) {
                    ForegroundTracker.onActivityResumed(activity)
                    com.better.heybox.SessionGuard.heartbeat()
                    val decor: View = activity.window.decorView
                    decor.postDelayed({ showVersionNotice(activity, cl) }, 600L)
                }
                result
            }
            module.logd(Log.INFO, MainModule.TAG, "✔ Heybox 版本检测 Hook 已安装")
        } catch (t: Throwable) {
            module.logd(Log.ERROR, MainModule.TAG, "✘ Heybox 版本检测 Hook 失败", t)
        }
    }

    private fun showVersionNotice(activity: Activity, cl: ClassLoader) {
        LogRecorder.setContext(activity)
        if (activity.isFinishing || VERSION_NOTICE_SHOWN.get()) {
            return
        }
        var version = "unknown"
        try {
            val info: PackageInfo =
                activity.packageManager.getPackageInfo(MainModule.TARGET_PKG, 0)
            val name = info.versionName
            if (name != null) {
                version = name
            }
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "读取 Heybox 版本失败", t)
            return
        }
        if (MainModule.SUPPORTED_HEYBOX_VERSIONS.contains(version) ||
            !VERSION_NOTICE_SHOWN.compareAndSet(false, true)
        ) {
            return
        }

        val message = "BetterHeybox 支持 Heybox " +
                MainModule.SUPPORTED_HEYBOX_VERSIONS.joinToString(" / ") + "，当前检测到 $version"
        try {
            val toastUtil = Class.forName("com.max.hbutils.utils.f", false, cl)
            val showBottomHint = toastUtil.getDeclaredMethod("d", String::class.java)
            showBottomHint.invoke(null, message)
        } catch (t: Throwable) {
            Toast.makeText(activity.applicationContext, message, Toast.LENGTH_LONG).show()
        }
        module.logd(Log.WARN, MainModule.TAG, message)
    }

    private fun hookUpdateBlocking(cl: ClassLoader) {
        try {
            val manager = Class.forName("com.max.xiaoheihe.utils.AppUpdateManager", false, cl)
            var updateEntry: Method? = null
            for (method in manager.declaredMethods) {
                if (method.name == "P" && method.parameterCount == 1 &&
                    method.parameterTypes[0] == java.lang.Boolean::class.java
                ) {
                    updateEntry = method
                    break
                }
            }
            if (updateEntry == null) {
                module.logd(Log.WARN, MainModule.TAG, "✘ 未找到 AppUpdateManager.P(Boolean)")
                return
            }
            module.hook(updateEntry).intercept { chain ->
                if (module.isEnabled(App.KEY_BLOCK_UPDATE, false)) {
                    module.logd(Log.INFO, MainModule.TAG, "已屏蔽 Heybox 更新入口 AppUpdateManager.P()")
                    return@intercept chain.instanceOrNull
                }
                chain.proceed()
            }
            module.logd(Log.INFO, MainModule.TAG, "✔ Heybox 更新屏蔽 Hook 已安装")
        } catch (t: Throwable) {
            module.logd(Log.ERROR, MainModule.TAG, "✘ Heybox 更新屏蔽 Hook 失败", t)
        }
    }

    companion object {
        private val MEDIA_KEYWORDS = listOf(
            "image", "photo", "preview", "gallery", "media", "picture", "browse"
        )
        private val VERSION_NOTICE_SHOWN = AtomicBoolean(false)
        private val DOWNGRADE_NOTICE_SHOWN = AtomicBoolean(false)
        private val UPDATE_NOTICE_SHOWN = AtomicBoolean(false)
        private val NOTICE_CALLBACKS = ArrayList<Pair<Application, Application.ActivityLifecycleCallbacks>>()

        @JvmStatic
        fun notifyDowngraded(app: Any?) =
            notifyOnce(app, DOWNGRADE_NOTICE_SHOWN, "BetterHeybox 已停用：模块过时，请更新模块后重启小黑盒")

        @JvmStatic
        fun notifyModuleUpdated(app: Any?) =
            notifyOnce(app, UPDATE_NOTICE_SHOWN, "模块已更新，建议重启小黑盒以完整生效")

        private fun notifyOnce(app: Any?, guard: AtomicBoolean, message: String) {
            val application = app as? Application ?: return
            lateinit var callbacks: Application.ActivityLifecycleCallbacks
            callbacks = object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                override fun onActivityStarted(activity: Activity) {}
                override fun onActivityResumed(activity: Activity) {
                    removeNoticeCallback(application, this)
                    if (!guard.compareAndSet(false, true)) {
                        return
                    }
                    try {
                        val toastUtil = Class.forName(
                            "com.max.hbutils.utils.f", false, activity.classLoader
                        )
                        val showBottomHint = toastUtil.getDeclaredMethod("d", String::class.java)
                        showBottomHint.invoke(null, message)
                    } catch (t: Throwable) {
                        Toast.makeText(activity.applicationContext, message, Toast.LENGTH_LONG).show()
                    }
                }

                override fun onActivityPaused(activity: Activity) {}
                override fun onActivityStopped(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            }
            synchronized(NOTICE_CALLBACKS) {
                NOTICE_CALLBACKS.add(application to callbacks)
            }
            application.registerActivityLifecycleCallbacks(callbacks)
        }

        private fun removeNoticeCallback(
            application: Application,
            callbacks: Application.ActivityLifecycleCallbacks
        ) {
            synchronized(NOTICE_CALLBACKS) {
                NOTICE_CALLBACKS.remove(application to callbacks)
            }
            try {
                application.unregisterActivityLifecycleCallbacks(callbacks)
            } catch (ignored: Throwable) {
            }
        }

        @JvmStatic
        fun shutdown() {
            val callbacks = synchronized(NOTICE_CALLBACKS) {
                ArrayList(NOTICE_CALLBACKS).also { NOTICE_CALLBACKS.clear() }
            }
            for ((application, callback) in callbacks) {
                try {
                    application.unregisterActivityLifecycleCallbacks(callback)
                } catch (ignored: Throwable) {
                }
            }
        }
    }
}
