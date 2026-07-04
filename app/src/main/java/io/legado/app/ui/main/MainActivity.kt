package io.legado.app.ui.main

import android.os.Bundle
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentStatePagerAdapter
import androidx.lifecycle.lifecycleScope
import io.legado.app.BuildConfig
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppConst.appInfo
import io.legado.app.constant.EventBus
import io.legado.app.databinding.ActivityMainBinding
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.storage.Backup
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.main.bookshelf.style1.BookshelfFragment1
import io.legado.app.ui.main.bookshelf.style2.BookshelfFragment2
import io.legado.app.utils.isCreated
import io.legado.app.utils.navigationBarHeight
import io.legado.app.utils.observeEvent
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import splitties.views.bottomPadding
import kotlin.coroutines.resume

/**
 * 主界面
 */
@Suppress("PrivatePropertyName")
class MainActivity : VMBaseActivity<ActivityMainBinding, MainViewModel>(),
    MainViewModel.CallBack {

    override val binding by viewBinding(ActivityMainBinding::inflate)
    override val viewModel by viewModels<MainViewModel>()
    private val idBookshelf1 = 11
    private val idBookshelf2 = 12
    private var exitTime: Long = 0
    private val EXIT_INTERVAL = 2000L

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        with(binding) {
            viewPagerMain.apply {
                offscreenPageLimit = 1
                adapter = BookshelfPagerAdapter(supportFragmentManager)
                setEdgeEffectColor(primaryColor)
            }
        }
        onBackPressedDispatcher.addCallback(this) {
            if (System.currentTimeMillis() - exitTime > EXIT_INTERVAL) {
                toastOnUi(R.string.double_click_exit)
                exitTime = System.currentTimeMillis()
            } else {
                finish()
            }
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        lifecycleScope.launch {
            upVersion()
            viewModel.setActivityCallback(this@MainActivity)
            if (AppConfig.autoRefreshBook) {
                viewModel.upAllBookToc()
            }
            viewModel.postLoad()
        }
    }

    private suspend fun upVersion() = suspendCancellableCoroutine sc@{ block ->
        LocalConfig.versionCode = appInfo.versionCode
        block.resume(null)
    }

    private fun notifyAppCrash() {
        LocalConfig.appCrash = false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (AppConfig.autoRefreshBook) {
            outState.putBoolean("isAutoRefreshedBook", true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Coroutine.async {
            BookHelp.clearInvalidCache()
        }
        if (!BuildConfig.DEBUG) {
            Backup.autoBack(this)
        }
    }

    @Suppress("DEPRECATION")
    private inner class BookshelfPagerAdapter(fm: FragmentManager) :
        FragmentStatePagerAdapter(fm, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {

        override fun getCount(): Int = 1

        override fun getItem(position: Int): Fragment {
            return if (AppConfig.bookGroupStyle == 1) {
                BookshelfFragment2(position)
            } else {
                BookshelfFragment1(position)
            }
        }
    }

    override fun openImportUi(type: Int, source: String) {
        // Import dialogs removed
    }
}
