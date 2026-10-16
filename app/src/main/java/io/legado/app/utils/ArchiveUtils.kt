package io.legado.app.utils

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.legado.app.constant.AppPattern
import splitties.init.appCtx
import java.io.File

/**
 * 压缩包导入功能已移除。
 *
 * 原实现依赖 `me.zhanghai.android.libarchive`，该 AAR 为 4 个 ABI 各带一个 1.5–2.4MB 的原生库
 * （`libarchive-jni.so`，arm64 单个 1.94MB），是安装包体积的主要来源之一。
 *
 * 这里保留原有的 API 形状（调用方无需改动即可继续编译），但**不再具备任何解压能力**：
 * - [isArchive] 仍按后缀识别 zip/rar/7z，让导入流程能给出“不支持压缩包”的友好提示；
 * - [deCompress] / [getArchiveFilesName] 统一返回空集合，调用方原有的“空结果”分支会给出提示。
 */
@Suppress("unused", "MemberVisibilityCanBePrivate")
object ArchiveUtils {

    const val TEMP_FOLDER_NAME = "ArchiveTemp"

    // 临时目录 下次启动自动删除
    val TEMP_PATH: String by lazy {
        appCtx.externalCache.getFile(TEMP_FOLDER_NAME).createFolderReplace().absolutePath
    }

    fun deCompress(
        archiveUri: Uri,
        path: String = TEMP_PATH,
        filter: ((String) -> Boolean)? = null
    ): List<File> = emptyList()

    fun deCompress(
        archivePath: String,
        path: String = TEMP_PATH,
        filter: ((String) -> Boolean)? = null
    ): List<File> = emptyList()

    fun deCompress(
        archiveFile: File,
        path: String = TEMP_PATH,
        filter: ((String) -> Boolean)? = null
    ): List<File> = emptyList()

    fun deCompress(
        archiveDoc: DocumentFile,
        path: String = TEMP_PATH,
        filter: ((String) -> Boolean)? = null
    ): List<File> = emptyList()

    fun deCompress(
        archiveFileDoc: FileDoc,
        path: String = TEMP_PATH,
        filter: ((String) -> Boolean)? = null
    ): List<File> = emptyList()

    /* 遍历目录获取文件名 */
    fun getArchiveFilesName(fileUri: Uri, filter: ((String) -> Boolean)? = null): List<String> =
        emptyList()

    fun getArchiveFilesName(fileDoc: FileDoc, filter: ((String) -> Boolean)? = null): List<String> =
        emptyList()

    fun isArchive(name: String): Boolean {
        return AppPattern.archiveFileRegex.matches(name)
    }
}
