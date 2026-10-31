package com.colorbat.app

import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object OtaUpdateManager {

    private const val UPDATE_URL = "https://raw.githubusercontent.com/vudatz/colorbat/main/version.json"

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val changelog: String
    )

    fun checkUpdate(context: Context, isManual: Boolean = false, onComplete: ((Boolean) -> Unit)? = null) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val connection = (URL(UPDATE_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    requestMethod = "GET"
                    useCaches = false
                }

                if (connection.responseCode == 200) {
                    val jsonText = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(jsonText)
                    val remoteVersionCode = json.optInt("versionCode", 0)
                    val remoteVersionName = json.optString("versionName", "")
                    val apkUrl = json.optString("apkUrl", "")
                    val changelog = json.optString("changelog", "Bản cập nhật mới với nhiều cải tiến và sửa lỗi.")

                    val currentVersionCode = try {
                        val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            pInfo.longVersionCode.toInt()
                        } else {
                            @Suppress("DEPRECATION")
                            pInfo.versionCode
                        }
                    } catch (e: Exception) {
                        0
                    }

                    withContext(Dispatchers.Main) {
                        if (remoteVersionCode > currentVersionCode && apkUrl.isNotEmpty()) {
                            showUpdateDialog(context, UpdateInfo(remoteVersionCode, remoteVersionName, apkUrl, changelog))
                            onComplete?.invoke(true)
                        } else {
                            if (isManual) {
                                showNotice(context, "ColorBat đã ở phiên bản mới nhất (v$remoteVersionName)!")
                            }
                            onComplete?.invoke(false)
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        if (isManual) {
                            showNotice(context, "Không thể kết nối máy chủ kiểm tra cập nhật.")
                        }
                        onComplete?.invoke(false)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (isManual) {
                        showNotice(context, "Lỗi kiểm tra cập nhật: ${e.localizedMessage ?: "Vui lòng thử lại"}")
                    }
                    onComplete?.invoke(false)
                }
            }
        }
    }

    private fun showUpdateDialog(context: Context, updateInfo: UpdateInfo) {
        AlertDialog.Builder(context)
            .setTitle("Cập Nhật ColorBat v${updateInfo.versionName}")
            .setMessage("Đã có phiên bản mới!\n\nNội dung cập nhật:\n${updateInfo.changelog}")
            .setPositiveButton("Cập nhật ngay") { _, _ ->
                downloadAndInstallApk(context, updateInfo)
            }
            .setNegativeButton("Để sau", null)
            .show()
    }

    private fun downloadAndInstallApk(context: Context, updateInfo: UpdateInfo) {
        @Suppress("DEPRECATION")
        val progressDialog = ProgressDialog(context).apply {
            setTitle("Đang tải ColorBat v${updateInfo.versionName}")
            setMessage("Đang tải bản cập nhật OTA...")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            isIndeterminate = false
            max = 100
            progress = 0
            setCancelable(false)
            show()
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val apkFile = File(context.cacheDir, "ColorBat_Update.apk")
                if (apkFile.exists()) apkFile.delete()

                val url = URL(updateInfo.apkUrl)
                var connection = url.openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = true
                connection.connectTimeout = 15000
                connection.readTimeout = 30000

                // Xử lý chuyển hướng (GitHub redirect 302/301 sang AWS S3)
                var redirectCount = 0
                while (connection.responseCode in 300..399 && redirectCount < 5) {
                    val redirectUrl = connection.getHeaderField("Location") ?: break
                    connection.disconnect()
                    connection = (URL(redirectUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15000
                        readTimeout = 30000
                    }
                    redirectCount++
                }

                val totalLength = connection.contentLength
                val inputStream = connection.inputStream
                val outputStream = FileOutputStream(apkFile)

                val buffer = ByteArray(8192)
                var downloadedBytes = 0L
                var read: Int

                while (inputStream.read(buffer).also { read = it } != -1) {
                    outputStream.write(buffer, 0, read)
                    downloadedBytes += read
                    if (totalLength > 0) {
                        val progressPercent = ((downloadedBytes * 100) / totalLength).toInt()
                        withContext(Dispatchers.Main) {
                            progressDialog.progress = progressPercent
                        }
                    }
                }

                outputStream.flush()
                outputStream.close()
                inputStream.close()

                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    installApk(context, apkFile)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    showNotice(context, "Tải bản cập nhật thất bại: ${e.localizedMessage}")
                }
            }
        }
    }

    private fun installApk(context: Context, apkFile: File) {
        // Cách 1: Tự động cài đặt im lặng qua Shizuku nếu đã cấp quyền
        if (ShizukuHelper.hasPermission()) {
            try {
                val result = ShizukuHelper.execRootOrShizuku("pm install -r ${apkFile.absolutePath}")
                if (result.contains("Success", ignoreCase = true)) {
                    showNotice(context, "Cập nhật thành công qua Shizuku!")
                    return
                }
            } catch (e: Exception) {}
        }

        // Cách 2: Mở trình cài đặt gói chuẩn của Android qua FileProvider
        try {
            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(installIntent)
        } catch (e: Exception) {
            showNotice(context, "Không thể mở trình cài đặt APK: ${e.localizedMessage}")
        }
    }

    private fun showNotice(context: Context, message: String) {
        if (context is MainActivity) {
            val view = context.findViewById<android.view.View>(android.R.id.content)
            Snackbar.make(view, message, Snackbar.LENGTH_SHORT).show()
        }
    }
}
