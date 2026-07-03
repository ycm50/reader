import os, re

base = 'A:/Downloads/legado'

# Fix ReadBookActivity.kt remaining issues
path = base + '/app/src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt'
with open(path, 'r', encoding='utf-8') as f:
    content = f.read()

# Remove the AppWebDav.isOk reference
content = content.replace('                AppWebDav.isOk', '                // WebDAV removed')

# Fix volumeKeyPageOnPlay that references BaseReadAloudService
content = content.replace(
    '            if (!AppConfig.volumeKeyPageOnPlay && BaseReadAloudService.isPlay()) {\n                return@onKeyDown\n            }\n',
    '            if (!AppConfig.volumeKeyPageOnPlay) {\n                return@onKeyDown\n            }\n'
)

# Fix the read aloud menu item
old_menu = """            R.id.menu_read_aloud -> when {
                BaseReadAloudService.isRun -> showReadAloudDialog()
                else -> onClickReadAloud()
            }
"""
new_menu = """            R.id.menu_read_aloud -> {
                onClickReadAloud()
            }
"""
content = content.replace(old_menu, new_menu)

# Remove the showReadAloudDialog method
old_show = """    override fun showReadAloudDialog() {
        showDialogFragment<ReadAloudDialog>()
    }

"""
content = content.replace(old_show, '')

with open(path, 'w', encoding='utf-8') as f:
    f.write(content)
print('ReadBookActivity.kt fixed')

# Fix ReadBookViewModel.kt - replace syncBookProgress
path2 = base + '/app/src/main/java/io/legado/app/ui/book/read/ReadBookViewModel.kt'
with open(path2, 'r', encoding='utf-8') as f:
    content = f.read()

old_sync = """    fun syncBookProgress(
        book: Book,
        alertSync: ((progress: BookProgress) -> Unit)? = null
    ) {
        if (!AppConfig.syncBookProgress) return
        execute {
            AppWebDav.getBookProgress(book)
        }.onError {
            AppLog.put("拉取阅读进度失败《${book.name}》\n${it.localizedMessage}", it)
        }.onSuccess { progress ->
            progress ?: return@onSuccess
            if (progress.durChapterIndex == book.durChapterIndex && progress.durChapterPos == book.durChapterPos) {
                return@onSuccess
            }
            if (progress.durChapterIndex < book.durChapterIndex ||
                (progress.durChapterIndex == book.durChapterIndex
                        && progress.durChapterPos < book.durChapterPos)
            ) {
                alertSync?.invoke(progress)
            } else if (progress.durChapterIndex < book.simulatedTotalChapterNum()) {
                ReadBook.setProgress(progress)
                AppLog.put("自动同步阅读进度成功《${book.name}》 ${progress.durChapterTitle}")
                context.toastOnUi("已同步最新阅读进度")
            }
        }
    }

"""
new_sync = """    fun syncBookProgress(
        book: Book,
        alertSync: ((progress: BookProgress) -> Unit)? = null
    ) {
        // WebDAV sync removed
    }

"""
content = content.replace(old_sync, new_sync)

with open(path2, 'w', encoding='utf-8') as f:
    f.write(content)
print('ReadBookViewModel.kt fixed')

# Fix RuleUpdate.kt - change cacheSource signature
path3 = base + '/app/src/main/java/io/legado/app/model/RuleUpdate.kt'
with open(path3, 'r', encoding='utf-8') as f:
    content = f.read()

content = content.replace('import io.legado.app.data.entities.RuleSub\n', '')

old_func = """    suspend fun cacheSource(ruleSub: RuleSub): Boolean {
        val url = ruleSub.url
        val type = ruleSub.type
        val silentUpdate = ruleSub.silentUpdate
        val update = ruleSub.update
        val updateInterval = ruleSub.updateInterval
        if (update + updateInterval * 3600 * 1000L > System.currentTimeMillis()) {
            return false
        } else {
            ruleSub.update = System.currentTimeMillis()
            appDb.ruleSubDao.update(ruleSub)
        }
        var upRules = false
        okHttpClient.newCallResponseBody {
            if (url.endsWith("#requestWithoutUA")) {
                url(url.substringBeforeLast("#requestWithoutUA"))
                header(AppConst.UA_NAME, "null")
            } else {
                url(url)
            }
        }.decompressed().byteStream().use {
            when (type) {
                0 -> GSON.fromJsonArray<BookSource>(it).getOrThrow().let { lists ->
                    val source = lists.firstOrNull() ?: return@let
                    if (source.bookSourceUrl.isEmpty()) {
                        throw NoStackTraceException("不是书源")
                    }
                    lists.forEach { list ->
                        val localSource = appDb.bookSourceDao.getBookSourcePart(list.bookSourceUrl)
                        if (localSource == null || localSource.lastUpdateTime < list.lastUpdateTime) {
                            if (silentUpdate) {
                                if (localSource != null) {
                                    list.bookSourceGroup = localSource.bookSourceGroup
                                }
                                SourceHelp.insertBookSource(list)
                                upRules = true
                            }
                            else {
                                cacheBookSourceMap[url] = lists
                                return true
                            }
                        }
                    }
                }
                2 -> GSON.fromJsonArray<ReplaceRule>(it).getOrThrow().let { lists ->
                    lists.forEach { list ->
                        val oldRule = appDb.replaceRuleDao.findById(list.id)
                        if (oldRule == null || list.pattern != oldRule.pattern || list.replacement != oldRule.replacement) {
                            if (silentUpdate) {
                                appDb.replaceRuleDao.insert(list)
                                upRules = true
                            }
                            else {
                                cacheReplaceRuleMap[url] = lists
                                return true
                            }
                        }
                    }
                }
            }
            if (upRules) {
                ContentProcessor.upReplaceRules()
            }
        }
        return false
    }"""

new_func = """    suspend fun cacheSource(url: String, type: Int, silentUpdate: Boolean): Boolean {
        var upRules = false
        okHttpClient.newCallResponseBody {
            if (url.endsWith("#requestWithoutUA")) {
                url(url.substringBeforeLast("#requestWithoutUA"))
                header(AppConst.UA_NAME, "null")
            } else {
                url(url)
            }
        }.decompressed().byteStream().use {
            when (type) {
                0 -> GSON.fromJsonArray<BookSource>(it).getOrThrow().let { lists ->
                    val source = lists.firstOrNull() ?: return@let
                    if (source.bookSourceUrl.isEmpty()) {
                        throw NoStackTraceException("不是书源")
                    }
                    lists.forEach { list ->
                        val localSource = appDb.bookSourceDao.getBookSourcePart(list.bookSourceUrl)
                        if (localSource == null || localSource.lastUpdateTime < list.lastUpdateTime) {
                            if (silentUpdate) {
                                if (localSource != null) {
                                    list.bookSourceGroup = localSource.bookSourceGroup
                                }
                                SourceHelp.insertBookSource(list)
                                upRules = true
                            }
                            else {
                                cacheBookSourceMap[url] = lists
                                return true
                            }
                        }
                    }
                }
                2 -> GSON.fromJsonArray<ReplaceRule>(it).getOrThrow().let { lists ->
                    lists.forEach { list ->
                        val oldRule = appDb.replaceRuleDao.findById(list.id)
                        if (oldRule == null || list.pattern != oldRule.pattern || list.replacement != oldRule.replacement) {
                            if (silentUpdate) {
                                appDb.replaceRuleDao.insert(list)
                                upRules = true
                            }
                            else {
                                cacheReplaceRuleMap[url] = lists
                                return true
                            }
                        }
                    }
                }
            }
            if (upRules) {
                ContentProcessor.upReplaceRules()
            }
        }
        return false
    }"""

content = content.replace(old_func, new_func)

with open(path3, 'w', encoding='utf-8') as f:
    f.write(content)
print('RuleUpdate.kt fixed')

# Fix MainViewModel.kt - remove ruleSubsUp
path4 = base + '/app/src/main/java/io/legado/app/ui/main/MainViewModel.kt'
with open(path4, 'r', encoding='utf-8') as f:
    content = f.read()

old_rs = """    fun ruleSubsUp() {
        execute {
            val ruleSubs = appDb.ruleSubDao.all
            for (ruleSub in ruleSubs) {
                if (ruleSub.autoUpdate) {
                    val checkResult = RuleUpdate.cacheSource(ruleSub)
                    if(checkResult) {
                        callback?.openImportUi(ruleSub.type, ruleSub.url)
                    }
                }
            }
        }
    }"""
new_rs = """    fun ruleSubsUp() {
        // RuleSub update removed
    }"""
content = content.replace(old_rs, new_rs)

with open(path4, 'w', encoding='utf-8') as f:
    f.write(content)
print('MainViewModel.kt fixed')

print('\nAll remaining fixes applied.')
