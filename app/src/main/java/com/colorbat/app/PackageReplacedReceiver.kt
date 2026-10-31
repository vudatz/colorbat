package com.colorbat.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.io.File

class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return
        if (intent?.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            // Tự động dọn dẹp toàn bộ file APK cài đặt trong bộ nhớ tạm sau khi nâng cấp thành công
            try {
                context.cacheDir?.listFiles()?.forEach { file ->
                    if (file.name.endsWith(".apk", ignoreCase = true)) {
                        file.delete()
                    }
                }
                context.externalCacheDir?.listFiles()?.forEach { file ->
                    if (file.name.endsWith(".apk", ignoreCase = true)) {
                        file.delete()
                    }
                }
            } catch (e: Exception) {}
        }
    }
}
