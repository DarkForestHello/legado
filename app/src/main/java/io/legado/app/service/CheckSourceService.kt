package io.legado.app.service

import android.content.Intent
import androidx.core.app.NotificationCompat
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.help.AppConfig
import io.legado.app.help.coroutine.CompositeCoroutine
import io.legado.app.model.CheckSource
import io.legado.app.model.Debug
import io.legado.app.model.NoStackTraceException
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.utils.activityPendingIntent
import io.legado.app.utils.postEvent
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import kotlin.math.min

class CheckSourceService : BaseService() {
    private var threadCount = AppConfig.threadCount
    private var searchCoroutine =
        Executors.newFixedThreadPool(min(threadCount, AppConst.MAX_THREAD)).asCoroutineDispatcher()
    private var tasks = CompositeCoroutine()
    private val allIds = ArrayList<String>()
    private val checkedIds = ArrayList<String>()
    private var processIndex = 0
    private var notificationMsg = ""
    private val notificationBuilder by lazy {
        NotificationCompat.Builder(this, AppConst.channelIdReadAloud)
            .setSmallIcon(R.drawable.ic_network_check)
            .setOngoing(true)
            .setContentTitle(getString(R.string.check_book_source))
            .setContentIntent(
                activityPendingIntent<BookSourceActivity>("activity")
            )
            .addAction(
                R.drawable.ic_stop_black_24dp,
                getString(R.string.cancel),
                servicePendingIntent<CheckSourceService>(IntentAction.stop)
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
    }

    override fun onCreate() {
        super.onCreate()
        notificationMsg = getString(R.string.start)
        upNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            IntentAction.start -> intent.getStringArrayListExtra("selectIds")?.let {
                check(it)
            }
            else -> stopSelf()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        super.onDestroy()
        Debug.finishChecking()
        tasks.clear()
        searchCoroutine.close()
        postEvent(EventBus.CHECK_SOURCE_DONE, 0)
    }

    private fun check(ids: List<String>) {
        if (allIds.isNotEmpty()) {
            toastOnUi("已有书源在校验,等完成后再试")
            return
        }
        tasks.clear()
        allIds.clear()
        checkedIds.clear()
        allIds.addAll(ids)
        processIndex = 0
        threadCount = min(allIds.size, threadCount)
        notificationMsg = getString(R.string.progress_show, "", 0, allIds.size)
        upNotification()
        for (i in 0 until threadCount) {
            check()
        }
    }

    /**
     * 检测
     */
    private fun check() {
        val index = processIndex
        synchronized(this) {
            processIndex++
        }
        execute(context = searchCoroutine) {
            if (index < allIds.size) {
                val sourceUrl = allIds[index]
                appDb.bookSourceDao.getBookSource(sourceUrl)?.let { source ->
                    check(source)
                } ?: onNext(sourceUrl, "")
            }
        }
    }

    fun check(source: BookSource) {
        execute(context = searchCoroutine) {
            Debug.startChecking(source)
            var searchWord = CheckSource.keyword
            source.ruleSearch?.checkKeyWord?.let {
                if (it.isNotBlank()) {
                    searchWord = it
                }
            }
            var books = WebBook.searchBookAwait(this, source, searchWord)
            if (books.isEmpty()) {
                source.addGroup("搜索失效")
                val exs = source.exploreKinds
                var url: String? = null
                for (ex in exs) {
                    url = ex.url
                    if (!url.isNullOrBlank()) {
                        break
                    }
                }
                if (url.isNullOrBlank()) {
                    throw NoStackTraceException("搜索内容为空并且没有发现")
                }
                books = WebBook.exploreBookAwait(this, source, url)
                if (books.isEmpty()) {
                    throw NoStackTraceException("发现书籍为空")
                }
            } else {
                source.removeGroup("搜索失效")
            }
            var book = books.first().toBook()
            if (book.tocUrl.isBlank()) {
                book = WebBook.getBookInfoAwait(this, source, book)
            }
            val toc = WebBook.getChapterListAwait(this, source, book)
            val nextChapterUrl = toc.getOrNull(1)?.url ?: toc.first().url
            val content = WebBook.getContentAwait(
                this,
                bookSource = source,
                book = book,
                bookChapter = toc.first(),
                nextChapterUrl = nextChapterUrl,
                needSave = false
            )
            if ( !toc.first().isVolume && content.isBlank()) {
                throw NoStackTraceException("正文内容为空")
            }
        }.timeout(180000L)
            .onError(searchCoroutine) {
                source.addGroup("失效")
                if (source.bookSourceComment?.contains("Error: ") == false) {
                    source.bookSourceComment =
                        "Error: ${it.localizedMessage} \n\n" + "${source.bookSourceComment}"
                }
                Debug.updateFinalMessage(source.bookSourceUrl, "失败:${it.localizedMessage}")
            }.onSuccess(searchCoroutine) {
                source.removeGroup("失效")
                source.bookSourceComment = source.bookSourceComment
                    ?.split("\n\n")
                    ?.filterNot {
                        it.startsWith("Error: ")
                    }?.joinToString("\n")
                Debug.updateFinalMessage(source.bookSourceUrl, "成功")
            }.onFinally(searchCoroutine) {
                source.respondTime = Debug.getRespondTime(source.bookSourceUrl)
                appDb.bookSourceDao.update(source)
                onNext(source.bookSourceUrl, source.bookSourceName)
            }
    }

    private fun onNext(sourceUrl: String, sourceName: String) {
        synchronized(this) {
            check()
            checkedIds.add(sourceUrl)
            notificationMsg =
                getString(R.string.progress_show, sourceName, checkedIds.size, allIds.size)
            upNotification()
            if (processIndex > allIds.size + threadCount - 1) {
                stopSelf()
            }
        }
    }

    /**
     * 更新通知
     */
    private fun upNotification() {
        notificationBuilder.setContentText(notificationMsg)
        notificationBuilder.setProgress(allIds.size, checkedIds.size, false)
        postEvent(EventBus.CHECK_SOURCE, notificationMsg)
        startForeground(AppConst.notificationIdCheckSource, notificationBuilder.build())
    }

}