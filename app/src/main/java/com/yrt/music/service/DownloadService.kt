package com.yrt.music.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.yrt.music.MainActivity
import com.yrt.music.YrtApp
import com.yrt.music.data.model.DownloadBatch
import com.yrt.music.data.model.DownloadStatus
import com.yrt.music.data.model.DownloadTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Android 14+ foreground service providing keepalive and real-time status notifications
 * for single and batch downloads.
 */
class DownloadService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Main)
    private var observerJob: Job? = null

    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForegroundAndSelf()
                return START_NOT_STICKY
            }
            ACTION_CANCEL_ALL -> {
                val app = application as? YrtApp
                val manager = app?.container?.downloadManager
                manager?.let { mgr ->
                    mgr.tasks.value.forEach { task ->
                        if (task.status == DownloadStatus.Queued || task.status == DownloadStatus.Running) {
                            mgr.cancel(task.id)
                        }
                    }
                    mgr.batches.value.forEach { batch ->
                        if (!batch.isCompleted && !batch.isCancelled) {
                            mgr.cancelBatch(batch.id)
                        }
                    }
                }
                stopForegroundAndSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForegroundWithInitialNotification()
                startObserving()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundWithInitialNotification() {
        val initialNotification = buildNotification(
            NotificationContent(
                title = "YRT İndirmeler",
                text = "İndirmeler hazırlanıyor…",
                progress = 0,
                indeterminate = true,
            )
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }
    }

    private fun startObserving() {
        observerJob?.cancel()
        val app = application as? YrtApp ?: return
        val downloadManager = app.container.downloadManager

        observerJob = serviceScope.launch {
            combine(downloadManager.tasks, downloadManager.batches) { tasks, batches ->
                Pair(tasks, batches)
            }.collect { (tasks, batches) ->
                if (!shouldServiceRun(tasks)) {
                    stopForegroundAndSelf()
                } else {
                    val content = formatNotificationContent(tasks, batches)
                    val notification = buildNotification(content)
                    notificationManager.notify(NOTIFICATION_ID, notification)
                }
            }
        }
    }

    private fun buildNotification(content: NotificationContent): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0),
        )

        val cancelAllIntent = Intent(this, DownloadService::class.java).apply {
            action = ACTION_CANCEL_ALL
        }
        val cancelAllPendingIntent = PendingIntent.getService(
            this,
            1,
            cancelAllIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0),
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(content.maxProgress, content.progress, content.indeterminate)
            .addAction(0, "İptal Et", cancelAllPendingIntent)
            .build()
    }

    private fun stopForegroundAndSelf() {
        observerJob?.cancel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "YRT indirme ilerleme durumu"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }

    data class NotificationContent(
        val title: String,
        val text: String,
        val progress: Int,
        val maxProgress: Int = 100,
        val indeterminate: Boolean = false,
    )

    companion object {
        const val CHANNEL_ID = "yrt_download_channel"
        const val CHANNEL_NAME = "İndirmeler"
        const val NOTIFICATION_ID = 2001

        const val ACTION_START = "com.yrt.music.action.START_DOWNLOAD_SERVICE"
        const val ACTION_STOP = "com.yrt.music.action.STOP_DOWNLOAD_SERVICE"
        const val ACTION_CANCEL_ALL = "com.yrt.music.action.CANCEL_ALL_DOWNLOADS"

        /**
         * Evaluates true if any task is actively running or waiting in queue.
         */
        fun shouldServiceRun(tasks: List<DownloadTask>): Boolean {
            return tasks.any { it.status == DownloadStatus.Queued || it.status == DownloadStatus.Running }
        }

        /**
         * Clamps progress value to valid integer range [0, 100].
         */
        fun clampProgress(progress: Int): Int = progress.coerceIn(0, 100)

        /**
         * Formats estimated remaining seconds into human-readable text.
         */
        fun formatEta(seconds: Long): String {
            if (seconds <= 0) return ""
            val m = seconds / 60
            val s = seconds % 60
            return if (m > 0) "${m}dk ${s}sn" else "${s}sn"
        }

        /**
         * Generates notification title, text, and progress from the current download state.
         */
        fun formatNotificationContent(
            tasks: List<DownloadTask>,
            batches: List<DownloadBatch>,
        ): NotificationContent {
            val activeTasks = tasks.filter { it.status == DownloadStatus.Queued || it.status == DownloadStatus.Running }
            if (activeTasks.isEmpty()) {
                return NotificationContent(
                    title = "İndirmeler Tamamlandı",
                    text = "Tüm indirmeler başarıyla tamamlandı",
                    progress = 100,
                )
            }

            // Check if there is an active bulk download batch
            val activeBatch = batches.firstOrNull { !it.isCompleted && !it.isCancelled && it.totalCount > 0 }
            if (activeBatch != null) {
                val runningTask = activeTasks.firstOrNull { it.status == DownloadStatus.Running && it.batchId == activeBatch.id }
                    ?: activeTasks.firstOrNull { it.batchId == activeBatch.id }
                val subtitle = if (runningTask != null) {
                    "${activeBatch.completedCount}/${activeBatch.totalCount} indirildi • ${runningTask.title}"
                } else {
                    "${activeBatch.completedCount}/${activeBatch.totalCount} indirildi"
                }
                return NotificationContent(
                    title = activeBatch.title,
                    text = subtitle,
                    progress = clampProgress(activeBatch.progress.toInt()),
                )
            }

            // Single task download active
            val runningTask = activeTasks.firstOrNull { it.status == DownloadStatus.Running } ?: activeTasks.first()
            val etaText = if (runningTask.etaSec > 0) " • Kalan: ${formatEta(runningTask.etaSec)}" else ""
            return NotificationContent(
                title = runningTask.title,
                text = "${clampProgress(runningTask.progress.toInt())}%$etaText",
                progress = clampProgress(runningTask.progress.toInt()),
            )
        }

        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Throwable) {
                // Background start restriction fallback
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (_: Throwable) {
                // Safe ignore
            }
        }
    }
}
