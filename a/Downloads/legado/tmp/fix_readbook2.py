import os

base = r'A:\Downloads\legado'
src_path = os.path.join(base, r'app\src\main\java\io\legado\app\ui\book\read\ReadBookActivity.kt')
tmp_path = os.path.join(base, r'tmp\ReadBookOrig.kt')

with open(tmp_path, 'r', encoding='utf-8') as f:
    content = f.read()

# 1. Remove imports for deleted classes
for imp in [
    'import io.legado.app.help.AppWebDav\n',
    'import io.legado.app.help.TTS\n',
    'import io.legado.app.service.BaseReadAloudService\n',
    'import io.legado.app.ui.book.read.config.ReadAloudDialog\n',
]:
    content = content.replace(imp, '')

# 2. Remove ReadAloudDialog.CallBack from class declaration
content = content.replace('    ReadAloudDialog.CallBack,\n', '')

# 3. Remove tts: TTS? = null
content = content.replace('    private var tts: TTS? = null\n', '')

# 4. Fix onBackPressed
old = '''            if (BaseReadAloudService.isPlay()) {
                ReadAloud.pause(this@ReadBookActivity)
                toastOnUi(R.string.read_aloud_pause)
                return@addCallback
            }
'''
content = content.replace(old, '')

# 5. Fix onPause
old = '''        if (!BuildConfig.DEBUG && ReadBook.inBookshelf) {
            if (AppConfig.syncBookProgressPlus) {
                ReadBook.syncProgress()
            } else {
                ReadBook.uploadProgress()
            }
        }
'''
content = content.replace(old, '        if (!BuildConfig.DEBUG && ReadBook.inBookshelf) {\n        }\n')

# 6. Remove ReadAloud.stop(this) from autoPage
content = content.replace('        ReadAloud.stop(this)\n', '')

# 7. Remove showReadAloudDialog
old = '''    /**
     * 朗读对话框
     */
    override fun showReadAloudDialog() {
        showDialogFragment<ReadAloudDialog>()
    }

'''
content = content.replace(old, '')

# 8. Fix onClickReadAloud
old = '''    /**
     * 朗读按钮
     */
    override fun onClickReadAloud() {
        autoPageStop()
        when {
            !BaseReadAloudService.isRun -> {
                ReadAloud.upReadAloudClass()
                val scrollPageAnim = ReadBook.pageAnim() == 3
                if (scrollPageAnim) {
                    val pos = binding.readView.getReadAloudPos()
                    if (pos != null) {
                        val (index, line) = pos
                        if (ReadBook.durChapterIndex != index) {
                            ReadBook.openChapter(index, line.chapterPosition, false) {
                                ReadBook.readAloud(startPos = line.pagePosition)
                            }
                        } else {
                            ReadBook.durChapterPos = line.chapterPosition
                            ReadBook.readAloud(startPos = line.pagePosition)
                        }
                    } else {
                        ReadBook.readAloud()
                    }
                } else {
                    ReadBook.readAloud()
                }
            }

            BaseReadAloudService.pause -> {
                val scrollPageAnim = ReadBook.pageAnim() == 3
                if (scrollPageAnim && pageChanged) {
                    pageChanged = false
                    val pos = binding.readView.getReadAloudPos()
                    if (pos != null) {
                        val (index, line) = pos
                        if (ReadBook.durChapterIndex != index) {
                            ReadBook.openChapter(index, line.chapterPosition, false) {
                                ReadBook.readAloud(startPos = line.pagePosition)
                            }
                        } else {
                            ReadBook.durChapterPos = line.chapterPosition
                            ReadBook.readAloud(startPos = line.pagePosition)
                        }
                    } else {
                        ReadBook.readAloud()
                    }
                } else {
                    ReadAloud.resume(this)
                }
            }

            else -> ReadAloud.pause(this)
        }
    }

'''
new = '''    /**
     * 朗读按钮
     */
    override fun onClickReadAloud() {
        autoPageStop()
        ReadBook.readAloud()
    }

'''
content = content.replace(old, new)

# 9. Fix onResume networkChanged
old = '''        networkChangedListener.register()
        networkChangedListener.onNetworkChanged = {
            // 当网络是可用状态且无需初始化时同步进度（初始化中已有同步进度逻辑）
            if (AppConfig.syncBookProgressPlus && NetworkUtils.isAvailable() && !justInitData && ReadBook.inBookshelf) {
                ReadBook.syncProgress({ progress -> sureNewProgress(progress) })
            }
        }
'''
new = '''        networkChangedListener.register()
        networkChangedListener.onNetworkChanged = {
        }
'''
content = content.replace(old, new)

# 10. Remove MEDIA_BUTTON, ALOUD_STATE, TTS_PROGRESS event handlers
old = '''        observeEvent<Boolean>(EventBus.MEDIA_BUTTON) {
            if (it) {
                onClickReadAloud()
            } else {
                ReadBook.readAloud(!BaseReadAloudService.pause)
            }
        }
'''
content = content.replace(old, '')

old = '''        observeEvent<Int>(EventBus.ALOUD_STATE) {
            if (it == Status.STOP || it == Status.PAUSE) {
                ReadBook.curTextChapter?.let { textChapter ->
                    val page = textChapter.getPageByReadPos(ReadBook.durChapterPos)
                    if (page != null) {
                        page.removePageAloudSpan()
                        readView.upContent(resetPageOffset = false)
                    }
                }
            }
        }
'''
content = content.replace(old, '')

old = '''        observeEventSticky<Int>(EventBus.TTS_PROGRESS) { chapterStart ->
            lifecycleScope.launch(IO) {
                if (BaseReadAloudService.isPlay()) {
                    ReadBook.curTextChapter?.let { textChapter ->
                        ReadBook.durChapterPos = chapterStart
                        val pageIndex = ReadBook.durPageIndex
                        val aloudSpanStart = chapterStart - textChapter.getReadLength(pageIndex)
                        textChapter.getPage(pageIndex)
                            ?.upPageAloudSpan(aloudSpanStart)
                        upContent()
                    }
                }
            }
        }
'''
content = content.replace(old, '')

# 11. Fix startBackupJob
old = '''    private fun startBackupJob() {
        backupJob?.cancel()
        backupJob = lifecycleScope.launch(IO) {
            delay(300000)
            ReadBook.book?.let {
                AppWebDav.uploadBookProgress(it)
                ensureActive()
                it.update()
                Backup.autoBack(this@ReadBookActivity)
            }
        }
    }

'''
new = '''    private fun startBackupJob() {
        backupJob?.cancel()
        backupJob = lifecycleScope.launch(IO) {
            delay(300000)
            ReadBook.book?.let {
                ensureActive()
                it.update()
                Backup.autoBack(this@ReadBookActivity)
            }
        }
    }

'''
content = content.replace(old, new)

# 12. Remove tts?.clearTts()
content = content.replace('        tts?.clearTts()\n', '')

# 13. Remove now-unused imports
content = content.replace('import io.legado.app.constant.Status\n', '')
if 'import io.legado.app.utils.NetworkUtils' in content:
    content = content.replace('import io.legado.app.utils.NetworkUtils\n', '')

with open(src_path, 'w', encoding='utf-8') as f:
    f.write(content)

print('Written successfully, lines:', len(content.splitlines()))
