package com.colorbat.app

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

object ShizukuHelper {

    fun isRunning(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
    }

    fun hasPermission(): Boolean {
        return if (isRunning()) {
            try {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } catch (e: Exception) {
                false
            }
        } else {
            false
        }
    }

    fun requestPermission(requestCode: Int = 1001) {
        if (isRunning() && !hasPermission()) {
            try {
                Shizuku.requestPermission(requestCode)
            } catch (e: Exception) {}
        }
    }

    fun execCommand(command: String): String? {
        if (!hasPermission()) return null
        return try {
            val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText().trim()
            process.waitFor()
            if (output.isNotEmpty()) output else null
        } catch (e: Exception) {
            null
        }
    }

    fun readSysfs(path: String): String? {
        // Nếu không có Shizuku, thử đọc trực tiếp bằng Java File (nếu kernel cho phép)
        try {
            val file = java.io.File(path)
            if (file.exists() && file.canRead()) {
                val content = file.readText().trim()
                if (content.isNotEmpty()) return content
            }
        } catch (e: Exception) {}

        // Nếu SELinux chặn hoặc không đọc được trực tiếp, mượn quyền Shizuku
        return execCommand("cat $path 2>/dev/null")
    }

    fun readSysfsInt(path: String): Int? {
        return readSysfs(path)?.trim()?.toIntOrNull()
    }
}
