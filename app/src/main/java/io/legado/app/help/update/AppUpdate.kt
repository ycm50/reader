package io.legado.app.help.update

import android.content.Context

data class UpdateInfo(
    val versionCode: Int = 0,
    val versionName: String = "",
    val downloadUrl: String = "",
    val changelog: String = ""
)

class AppUpdate(private val context: Context) {

    fun check(onResult: (UpdateInfo?) -> Unit) {
        // TODO: query update server and return the result
        onResult(null)
    }

    fun downloadAndInstall(updateInfo: UpdateInfo) {
        // TODO: download APK and trigger installation
    }
}
