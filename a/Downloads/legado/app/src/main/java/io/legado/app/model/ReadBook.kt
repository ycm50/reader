package io.legado.app.model

import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.PageAnim.scrollPageAnim
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.ReadRecord
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isPdf
import io.legado.app.help.book.isSameNameAuthor
import io.legado.app.help.book.readSimulating
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.book.update
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.globalExecutor
import io.legado.app.model.localBook.TextFile
import io.legado.app.model.webBook.WebBook
import io.legado.app.service.CacheBookService
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.book.read.page.provider.LayoutProgressListener
import io.legado.app.utils.postEvent
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min


@Suppress("MemberVisibilityCanBePrivate")
object ReadBook : CoroutineScope by MainScope() {
    var book: Book? = null
    var callBack: CallBack? = null
    var inBookshelf = false
    var chapterSize = 0
    var simulatedChapterSize = 0
    var durChapterIndex = 0
    var durChapterPos = 0
    var isLocalBook = true
    var chapterChanged = false
    var prevTextChapter: TextChapter? = null
    var curTextChapter: TextChapter? = null
    var nextTextChapter: TextChapter? = null
    var bookSource: BookSource? = null
    var msg: String? = null
    private val loadingChapters = arrayListOf<Int>()
    private val readRecord = ReadRecord()
    private val chapterLoadingJobs = ConcurrentHashMap<Int, Coroutine<*>>()
    private val prevChapterLoadingLock = Mutex()
    private val curChapterLoadingLock = Mutex()
    private val nextChapterLoadingLock = Mutex()
    var readStartTime: Long = System.currentTimeMillis()

    /* 跳转进度前进度记录 */
    var lastBookProgress: BookProgress? = null

    /* web端阅读进度记录 */
    var webBookProgress: BookProgress? = null

    var preDownloadTask: Job? = null
    val downloadedChapters = hashSetOf<Int>()
    val downloadFailChapters = hashMapOf<Int, Int>()
    var contentProcessor: ContentProcessor? = null
    val downloadScope = CoroutineScope(SupervisorJob() + IO)
    val preDownloadSemaphore = Semaphore(2)
    val executor = globalExecutor

    fun resetData(book: Book) {
        releaseAndCancel()
        ReadBook.book = book
        readRecord.bookName = book.name
        readRecord.readTime = appDb.readRecordDao.getReadTime(book.name) ?: 0
        chapterSize = appDb.bookChapterDao.getChapterCount(book.bookUrl)
        simulatedChapterSize = if (book.readSimulating()) {
            book.simulatedTotalChapterNum()
        } else {
            chapterSize
        }
        contentProcessor = ContentProcessor.get(book)
        durChapterIndex = book.durChapterIndex
        durChapterPos = book.durChapterPos
        isLocalBook = book.isLocal
        clearTextChapter()
        callBack?.upContent()
        callBack?.upMenuView()
        callBack?.upPageAnim()
        upWebBook(book)
        lastBookProgress = null
        webBookProgress = null
        TextFile.clear()
        synchronized(this) {
            loadingChapters.clear()
            downloadedChapters.clear()
            downloadFailChapters.clear()
        }
    }

    fun upData(book: Book) {
        releaseAndCancel()
        ReadBook.book = book
        chapterSize = appDb.bookChapterDao.getChapterCount(book.bookUrl)
        simulatedChapterSize = if (book.readSimulating()) {
            book.simulatedTotalChapterNum()
        } else {
            chapterSize
        }
        if (durChapterIndex != book.durChapterIndex) {
            durChapterIndex = book.durChapterIndex
            durChapterPos = book.durChapterPos
            clearTextChapter()
        }
        if (curTextChapter?.isCompleted == false) {
            curTextChapter = null
        }
        if (nextTextChapter?.isCompleted == false) {
            nextTextChapter = null
        }
        if (prevTextChapter?.isCompleted == false) {
            prevTextChapter = null
        }
        callBack?.upMenuView()
        upWebBook(book)
        synchronized(this) {
            loadingChapters.clear()
            downloadedChapters.clear()
            downloadFailChapters.clear()
        }
    }

    fun upWebBook(book: Book) {
        if (book.isLocal) {
            bookSource = null
            if (book.getImageStyle().isNullOrBlank() && (book.isImage || book.isPdf)) {
                book.setImageStyle(Book.imgStyleFull)
            }
        } else {
            appDb.bookSourceDao.getBookSource(book.origin)?.let {
                bookSource = it
                if (book.getImageStyle().isNullOrBlank()) {
                    var imageStyle = it.getContentRule().imageStyle
                    if (imageStyle.isNullOrBlank() && (book.isImage || book.isPdf)) {
                        imageStyle = Book.imgStyleSingle
                    }
                    book.setImageStyle(imageStyle)
                    if (imageStyle.equals(Book.imgStyleSingle, true)) {
                        book.setPageAnim(0)
                    }
                }
            } ?: let {
                bookSource = null
            }
        }
    }

    fun upReadBookConfig(book: Book) {
        val oldIndex = ReadBookConfig.styleSelect
        ReadBookConfig.isComic = book.isImage
        if (oldIndex != ReadBookConfig.styleSelect) {
            postEvent(EventBus.UP_CONFIG, arrayListOf(1, 2, 5))
            if (AppConfig.readBarStyleFollowPage) {
                postEvent(EventBus.UPDATE_READ_ACTION_BAR, true)
            }
        }
    }

    fun setProgress(progress: BookProgress) {
        if (progress.durChapterIndex < chapterSize &&
            (durChapterIndex != progress.durChapterIndex
                    || durChapterPos != progress.durChapterPos)
        ) {
            durChapterIndex = progress.durChapterIndex
            durChapterPos = progress.durChapterPos
            saveRead()
            clearTextChapter()
            callBack?.upContent()
            loadContent(resetPageOffset = true)
        }
    }

    //暂时保存跳转前进度
    fun saveCurrentBookProgress() {
        if (lastBookProgress != null) return //避免进度条连续跳转不能覆盖最初的进度记录
        lastBookProgress = book?.let { BookProgress(it) }
    }

    //恢复跳转前进度
    fun restoreLastBookProgress() {
        lastBookProgress?.let {
            setProgress(it)
            lastBookProgress = null
        }
    }

    fun clearTextChapter() {
        clearExpiredChapterLoadingJob(true)
        prevTextChapter = null
        curTextChapter = null
        nextTextChapter = null
    }

    fun clearSearchResult() {
        curTextChapter?.clearSearchResult()
        prevTextChapter?.clearSearchResult()
        nextTextChapter?.clearSearchResult()
    }

    fun upReadTime() {
        if (!AppConfig.enableReadRecord) {
            return
        }
        executor.execute {
            readRecord.readTime = readRecord.readTime + System.currentTimeMillis() - readStartTime
            readStartTime = System.currentTimeMillis()
            readRecord.lastRead = System.currentTimeMillis()
            appDb.readRecordDao.insert(readRecord)
        }
    }

    fun upMsg(msg: String?) {
        if (ReadBook.msg != msg) {
            ReadBook.msg = msg
            callBack?.upContent()
        }
    }

    fun moveToNextPage(): Boolean {
        var hasNextPage = false
        curTextChapter?.let {
            val nextPagePos = it.getNextPageLength(durChapterPos)
            if (nextPagePos >= 0) {
                hasNextPage = true
                it.getPage(durPageIndex)?.removePageAloudSpan()
                durChapterPos = nextPagePos
                callBack?.cancelSelect()
                callBack?.upContent()
                saveRead(true)
            }
        }
        return hasNextPage
    }

    fun moveToPrevPage(): Boolean {
        var hasPrevPage = false
        curTextChapter?.let {
            val prevPagePos = it.getPrevPageLength(durChapterPos)
            if (prevPagePos >= 0) {
                hasPrevPage = true
                durChapterPos = prevPagePos
                callBack?.upContent()
                saveRead(true)
            }
        }
        return hasPrevPage
    }

    fun moveToNextChapter(upContent: Boolean, upContentInPlace: Boolean = true): Boolean {
        if (durChapterIndex < simulatedChapterSize - 1) {
            durChapterPos = 0
            durChapterIndex++
            clearExpiredChapterLoadingJob()
            prevTextChapter = curTextChapter
            curTextChapter = nextTextChapter
            nextTextChapter = null
            if (curTextChapter == null) {
                AppLog.putDebug("moveToNextChapter-章节未加载,开始加载")
                if (upContentInPlace) callBack?.upContent()
                loadContent(durChapterIndex, upContent, resetPageOffset = false)
            } else if (upContent && upContentInPlace) {
                AppLog.putDebug("moveToNextChapter-章节已加载,刷新视图")
                callBack?.upContent()
            }
            loadContent(durChapterIndex.plus(1), upContent, false)
            saveRead()
            callBack?.upMenuView()
            AppLog.putDebug("moveToNextChapter-curPageChanged()")
            curPageChanged()
            return true
        } else {
            AppLog.putDebug("跳转下一章失败,没有下一章")
            return false
        }
    }

    suspend fun moveToNextChapterAwait(
        upContent: Boolean,
        upContentInPlace: Boolean = true
    ): Boolean {
        if (durChapterIndex < simulatedChapterSize - 1) {
            durChapterPos = 0
            durChapterIndex++
            clearExpiredChapterLoadingJob()
            prevTextChapter = curTextChapter
            curTextChapter = nextTextChapter
            nextTextChapter = null
            if (curTextChapter == null) {
                AppLog.putDebug("moveToNextChapterAwait-章节未加载,开始加载")
                if (upContentInPlace) callBack?.upContent()
                loadContentAwait(durChapterIndex, upContent, resetPageOffset = false)
            } else if (upContent && upContentInPlace) {
                AppLog.putDebug("moveToNextChapterAwait-章节已加载,刷新视图")
                callBack?.upContent()
            }
            loadContentAwait(durChapterIndex.plus(1), upContent, false)
            saveRead()
            callBack?.upMenuView()
            AppLog.putDebug("moveToNextChapterAwait-curPageChanged()")
            curPageChanged()
            return true
        } else {
            AppLog.putDebug("跳转下一章失败,没有下一章")
            return false
        }
    }

    fun moveToPrevChapter(upContent: Boolean): Boolean {
        if (durChapterIndex > 0) {
            durChapterPos = 0
            durChapterIndex--
            clearExpiredChapterLoadingJob()
            nextTextChapter = curTextChapter
            curTextChapter = prevTextChapter
            prevTextChapter = null
            if (curTextChapter == null) {
                if (upContent) callBack?.upContent()
                loadContent(durChapterIndex, upContent, resetPageOffset = false)
            } else if (upContent) {
                callBack?.upContent()
            }
            loadContent(durChapterIndex - 1, upContent, false)
            saveRead()
            callBack?.upMenuView()
            AppLog.putDebug("moveToPrevChapter-curPageChanged()")
            curPageChanged()
            return true
        } else {
            AppLog.putDebug("跳转上一章失败,没有上一章")
            return false
        }
    }

    suspend fun moveToPrevChapterAwait(upContent: Boolean): Boolean {
        if (durChapterIndex > 0) {
            durChapterPos = 0
            durChapterIndex--
            clearExpiredChapterLoadingJob()
            nextTextChapter = curTextChapter
            curTextChapter = prevTextChapter
            prevTextChapter = null
            if (curTextChapter == null) {
                if (upContent) callBack?.upContent()
                loadContentAwait(durChapterIndex, upContent, resetPageOffset = false)
            } else if (upContent) {
                callBack?.upContent()
            }
            loadContentAwait(durChapterIndex - 1, upContent, false)
            saveRead()
            callBack?.upMenuView()
            AppLog.putDebug("moveToPrevChapterAwait-curPageChanged()")
            curPageChanged()
            return true
        } else {
            AppLog.putDebug("跳转上一章失败,没有上一章")
            return false
        }
    }

    fun openChapter(
        index: Int,
        durChapterPos: Int = 0,
        upContent: Boolean = true,
        success: (() -> Unit)? = null
    ) {
        if (index >= 0 && index < simulatedChapterSize) {
            this.durChapterPos = durChapterPos
            this.durChapterIndex = index
            clearExpiredChapterLoadingJob()
            clearTextChapter()
            callBack?.upContent()
            loadContent(index, upContent, resetPageOffset = true, success = success)
        }
    }

    fun loadContent(
        resetPageOffset: Boolean = true,
        success: (() -> Unit)? = null
    ) {
        loadContent(durChapterIndex, true, resetPageOffset, success = success)
        loadContent(durChapterIndex - 1, false, false)
        loadContent(durChapterIndex + 1, false, false)
    }

    fun loadOrUpContent(success: (() -> Unit)? = null) {
        if (curTextChapter?.isCompleted == true) {
            callBack?.upContent()
            success?.invoke()
        } else {
            loadContent(resetPageOffset = false, success = success)
        }
    }

    suspend fun loadOrUpContentAwait() {
        if (curTextChapter?.isCompleted == true) {
            callBack?.upContent()
        } else {
            loadContentAwait()
        }
    }

    /**
     * 加载三个章节
     */
    private var contentLoadFinish = true

    fun loadContent(
        index: Int,
        upContent: Boolean = true,
        resetPageOffset: Boolean,
        canceled: Boolean = false,
        success: (() -> Unit)? = null
    ) {
        removeLoading(index)
        if (index !in durChapterIndex - 1..durChapterIndex + 1) {
            return
        }
        val chapter = appDb.bookChapterDao.getChapter(book?.bookUrl, index) ?: return
        loadContent(
            chapter,
            upContent,
            resetPageOffset,
            canceled,
            success
        )
    }

    private fun loadContent(
        chapter: BookChapter,
        upContent: Boolean = true,
        resetPageOffset: Boolean,
        canceled: Boolean = false,
        success: (() -> Unit)? = null
    ) {
        removeLoading(chapter.index)
        if (canceled || chapter.index !in durChapterIndex - 1..durChapterIndex + 1) {
            return
        }
        chapterLoadingJobs[chapter.index]?.cancel()
        val job = Coroutine.async(this, start = CoroutineStart.LAZY) {
            val contentProcessor = ContentProcessor.get(book.name, book.origin)
            val displayTitle = chapter.getDisplayTitle(
                contentProcessor.getTitleReplaceRules(),
                book.getUseReplaceRule(),
                replaceBook = book.toReplaceBook()
            )
            val contents = contentProcessor
                .getContent(book, chapter, content, includeTitle = false)
            ensureActive()
            val textChapter = ChapterProvider.getTextChapterAsync(
                this, book, chapter, displayTitle, contents, simulatedChapterSize
            )
            when (val offset = chapter.index - durChapterIndex) {
                0 -> curChapterLoadingLock.withLock {
                    withContext(Main) {
                        ensureActive()
                        curTextChapter = textChapter
                    }
                    callBack?.upMenuView()
                    var available = false
                    for (page in textChapter.layoutChannel) {
                        val index = page.index
                        if (!available && page.containPos(durChapterPos)) {
                            if (upContent) {
                                callBack?.upContent(offset, resetPageOffset)
                            }
                            available = true
                        }
                        if (upContent && isScroll) {
                            if (max(index - 3, 0) < durPageIndex) {
                                callBack?.upContent(offset, false)
                            }
                        }
                        callBack?.onLayoutPageCompleted(index, page)
                    }
                    if (upContent) callBack?.upContent(offset, !available && resetPageOffset)
                    curPageChanged()
                    callBack?.contentLoadFinish()
                }

                -1 -> prevChapterLoadingLock.withLock {
                    withContext(Main) {
                        ensureActive()
                        prevTextChapter = textChapter
                    }
                    textChapter.layoutChannel.receiveAsFlow().collect()
                    if (upContent) callBack?.upContent(offset, resetPageOffset)
                }

                1 -> nextChapterLoadingLock.withLock {
                    withContext(Main) {
                        ensureActive()
                        nextTextChapter = textChapter
                    }
                    for (page in textChapter.layoutChannel) {
                        if (page.index > 1) {
                            continue
                        }
                        if (upContent) callBack?.upContent(offset, resetPageOffset)
                    }
                }
            }

            return@async
        }.onError {
            if (it is CancellationException) {
                return@onError
            }
            AppLog.put("ChapterProvider ERROR", it)
            appCtx.toastOnUi("ChapterProvider ERROR:\n${it.stackTraceStr}")
        }.onSuccess {
            success?.invoke()
        }
        chapterLoadingJobs[chapter.index] = job
        job.start()
    }

    suspend fun contentLoadFinishAwait(
        book: Book,
        chapter: BookChapter,
        content: String,
        upContent: Boolean = true,
        resetPageOffset: Boolean
    ) {
        removeLoading(chapter.index)
        if (chapter.index !in durChapterIndex - 1..durChapterIndex + 1) {
            return
        }
        kotlin.runCatching {
            val contentProcessor = ContentProcessor.get(book.name, book.origin)
            val displayTitle = chapter.getDisplayTitle(
                contentProcessor.getTitleReplaceRules(),
                book.getUseReplaceRule(),
                replaceBook = book.toReplaceBook()
            )
            val contents = contentProcessor
                .getContent(book, chapter, content, includeTitle = false)
            val textChapter = ChapterProvider.getTextChapterAsync(
                this@ReadBook, book, chapter, displayTitle, contents, simulatedChapterSize
            )
            when (val offset = chapter.index - durChapterIndex) {
                0 -> {
                    curTextChapter?.cancelLayout()
                    withContext(Main) {
                        curTextChapter = textChapter
                    }
                    callBack?.upMenuView()
                    var available = false
                    for (page in textChapter.layoutChannel) {
                        val index = page.index
                        if (!available && page.containPos(durChapterPos)) {
                            if (upContent) {
                                callBack?.upContent(offset, resetPageOffset)
                            }
                            available = true
                        }
                        if (upContent && isScroll) {
                            if (max(index - 3, 0) < durPageIndex) {
                                callBack?.upContent(offset, false)
                            }
                        }
                        callBack?.onLayoutPageCompleted(index, page)
                    }
                    if (upContent) callBack?.upContent(offset, !available && resetPageOffset)
                    curPageChanged()
                    callBack?.contentLoadFinish()
                }

                -1 -> {
                    prevTextChapter?.cancelLayout()
                    withContext(Main) {
                        prevTextChapter = textChapter
                    }
                    textChapter.layoutChannel.receiveAsFlow().collect()
                    if (upContent) callBack?.upContent(offset, resetPageOffset)
                }

                1 -> {
                    nextTextChapter?.cancelLayout()
                    withContext(Main) {
                        nextTextChapter = textChapter
                    }
                    for (page in textChapter.layoutChannel) {
                        if (page.index > 1) {
                            continue
                        }
                        if (upContent) callBack?.upContent(offset, resetPageOffset)
                    }
                }
            }
        }.onFailure {
            if (it is CancellationException) {
                return@onFailure
            }
            AppLog.put("ChapterProvider ERROR", it)
            appCtx.toastOnUi("ChapterProvider ERROR:\n${it.stackTraceStr}")
        }
    }

    /**
     * 预下载时，章节已完，更新目录
     */
    @Synchronized
    fun upToc() {
        val bookSource = bookSource ?: return
        val book = book ?: return
        if (!book.canUpdate) return
        if (chapterSize - durChapterIndex - 1 >= 3) return
        if (System.currentTimeMillis() - book.lastCheckTime < 600000) return
        book.lastCheckTime = System.currentTimeMillis()
        val oldBook = book.copy()
        WebBook.getChapterList(this, bookSource, book).onSuccess(IO) { cList ->
            ensureActive()
            if (cList.size > chapterSize) {
                if (oldBook.bookUrl == book.bookUrl) {
                    appDb.bookDao.update(book)
                } else {
                    appDb.bookDao.replace(oldBook, book)
                    BookHelp.updateCacheFolder(oldBook, book)
                }
                appDb.bookChapterDao.delByBook(oldBook.bookUrl)
                appDb.bookChapterDao.insert(*cList.toTypedArray())
                onChapterListUpdated(book, false)
                nextTextChapter ?: loadContent(durChapterIndex + 1)
            }
        }
    }

    fun pageAnim(): Int {
        return book?.getPageAnim() ?: ReadBookConfig.pageAnim
    }

    fun setCharset(charset: String) {
        book?.let {
            it.charset = charset
            callBack?.loadChapterList(it)
        }
        saveRead()
    }

    fun saveRead(pageChanged: Boolean = false) {
        val book = book ?: return
        executor.execute {
            kotlin.runCatching {
                book.lastCheckCount = 0
                val durTime = System.currentTimeMillis()
                book.durChapterTime = durTime
                val chapterChanged = book.durChapterIndex != durChapterIndex
                book.durChapterIndex = durChapterIndex
                book.durChapterPos = durChapterPos
                if (!pageChanged || chapterChanged) {
                    appDb.bookChapterDao.getChapter(book.bookUrl, durChapterIndex)?.let {
                        book.durChapterTitle = it.getDisplayTitle(
                            ContentProcessor.get(book.name, book.origin).getTitleReplaceRules(),
                            book.getUseReplaceRule(),
                            replaceBook = book.toReplaceBook()
                        )
                        SourceCallBack.callBackBook(SourceCallBack.SAVE_READ, bookSource, book, it, durTime.toString())
                    }
                }
                book.update()
            }.onFailure {
                AppLog.put("保存书籍阅读进度信息出错\n$it", it)
            }
        }
    }

    /**
     * 预下载
     */
    private fun preDownload() {
        if (book?.isLocal == true) return
        executor.execute {
            if (AppConfig.preDownloadNum < 2) {
                upToc()
                return@execute
            }
            preDownloadTask?.cancel()
            preDownloadTask = launch(IO) {
                //预下载
                launch {
                    val maxChapterIndex =
                        min(durChapterIndex + AppConfig.preDownloadNum, chapterSize)
                    for (i in durChapterIndex.plus(2)..maxChapterIndex) {
                        if (downloadedChapters.contains(i)) continue
                        if ((downloadFailChapters[i] ?: 0) >= 3) continue
                        downloadIndex(i)
                    }
                }
                launch {
                    val minChapterIndex = durChapterIndex - min(5, AppConfig.preDownloadNum)
                    for (i in durChapterIndex.minus(2) downTo minChapterIndex) {
                        if (downloadedChapters.contains(i)) continue
                        if ((downloadFailChapters[i] ?: 0) >= 3) continue
                        downloadIndex(i)
                    }
                }
            }
        }
    }

    fun cancelPreDownloadTask() {
        if (contentLoadFinish) {
            preDownloadTask?.cancel()
            downloadScope.coroutineContext.cancelChildren()
        }
    }

    fun onChapterListUpdated(newBook: Book, loadContent: Boolean = true) {
        if (newBook.isSameNameAuthor(book)) {
            book = newBook
            chapterSize = newBook.totalChapterNum
            simulatedChapterSize = newBook.simulatedTotalChapterNum()
            if (simulatedChapterSize > 0 && durChapterIndex > simulatedChapterSize - 1) {
                durChapterIndex = simulatedChapterSize - 1
            }
            callBack?.upMenuView()
            if (callBack == null) {
                clearTextChapter()
            } else if (loadContent) {
                loadContent(true)
            }
        }
    }

    private fun clearExpiredChapterLoadingJob(clearAll: Boolean = false) {
        val iterator = chapterLoadingJobs.iterator()
        while (iterator.hasNext()) {
            val (index, job) = iterator.next()
            if (clearAll || index !in durChapterIndex - 1..durChapterIndex + 1) {
                job.cancel()
                iterator.remove()
            }
        }
    }

    /**
     * 注册回调
     */
    fun register(cb: CallBack) {
        callBack?.notifyBookChanged()
        callBack = cb
    }

    /**
     * 取消注册回调
     */
    fun unregister(cb: CallBack) {
        if (callBack === cb) {
            callBack = null
        }
        releaseAndCancel()
    }

    private fun releaseAndCancel() {
        msg = null
        preDownloadTask?.cancel()
        downloadScope.coroutineContext.cancelChildren()
        coroutineContext.cancelChildren()
        ImageProvider.clear()
        clearExpiredChapterLoadingJob(true)
        if (!CacheBookService.isRun) {
            CacheBook.close()
        }
    }

    interface CallBack : LayoutProgressListener {
        fun upMenuView()

        fun loadChapterList(book: Book)

        fun upContent(
            relativePosition: Int = 0,
            resetPageOffset: Boolean = true,
            success: (() -> Unit)? = null
        )

        suspend fun upContentAwait(
            relativePosition: Int = 0,
            resetPageOffset: Boolean = true,
            success: (() -> Unit)? = null
        )

        fun pageChanged()

        fun contentLoadFinish()

        fun upPageAnim(upRecorder: Boolean = false)

        fun notifyBookChanged()

        fun sureNewProgress(progress: BookProgress)

        fun cancelSelect()
    }

}
