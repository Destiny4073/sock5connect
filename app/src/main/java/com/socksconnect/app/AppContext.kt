package com.socksconnect.app

import android.content.Context

/** 供无 Context 的工具类读取字符串资源；在 Activity/Service onCreate 时初始化 */
internal object AppContext {
    @Volatile
    private lateinit var app: Context

    fun init(context: Context) {
        app = context.applicationContext
    }

    fun getString(resId: Int, vararg args: Any?): String =
        if (args.isEmpty()) app.getString(resId) else app.getString(resId, *args)
}
