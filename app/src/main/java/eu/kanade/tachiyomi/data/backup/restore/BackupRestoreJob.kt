package eu.kanade.tachiyomi.data.backup.restore

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import eu.kanade.tachiyomi.data.backup.BackupNotifier
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.isRunning
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR

class BackupRestoreJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val notifier = BackupNotifier(context)

    override suspend fun doWork(): Result {
        val uri = inputData.getString(LOCATION_URI_KEY)?.toUri()
        val options = inputData.getBooleanArray(OPTIONS_KEY)?.let { RestoreOptions.fromBooleanArray(it) }

        if (uri == null || options == null) {
            return Result.failure()
        }

        val isSync = inputData.getBoolean(SYNC_KEY, false)

        setForegroundSafely()

        return try {
            BackupRestorer(context, notifier, isSync).restore(uri, options)
            Result.success()
        } catch (e: Exception) {
            if (e is CancellationException) {
                notifier.showRestoreError(context.stringResource(MR.strings.restoring_backup_canceled))
                Result.success()
            } else {
                logcat(LogPriority.ERROR, e)
                notifier.showRestoreError(e.message)
                Result.failure()
            }
        } finally {
            context.cancelNotification(Notifications.ID_RESTORE_PROGRESS)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return ForegroundInfo(
            Notifications.ID_RESTORE_PROGRESS,
            notifier.showRestoreProgress().build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    companion object {
        fun isRunning(context: Context): Boolean {
            return context.workManager.isRunning(TAG)
        }

        fun start(
            context: Context,
            uri: Uri,
            options: RestoreOptions,
            sync: Boolean = false,
        ) {
            val inputData = workDataOf(
                LOCATION_URI_KEY to uri.toString(),
                SYNC_KEY to sync,
                OPTIONS_KEY to options.asBooleanArray(),
            )
            val request = OneTimeWorkRequestBuilder<BackupRestoreJob>()
                .addTag(TAG)
                .setInputData(inputData)
                .build()
            context.workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.KEEP, request)
        }

        /**
         * 直接执行备份恢复逻辑（挂起直到完成）
         * @param context 上下文
         * @param uri 本地已下载好的 .tachibk 文件的 Uri
         * @param options 恢复选项（书库、设置、历史记录等）
         * @param isSync 是否为同步模式（通常传 false）
         */
        suspend fun performSyncRestore(
            context: Context,
            uri: Uri,
            options: RestoreOptions,
            isSync: Boolean = false
        ): Boolean {
            val notifier = BackupNotifier(context)
            val restorer = BackupRestorer(context, notifier, isSync)

            try {
                logcat(LogPriority.INFO) { "开始从 URI 恢复备份: $uri" }
                // 直接调用 BackupRestorer 的 restore 方法
                restorer.restore(uri, options)
                return  true
            } catch (e: Exception) {
                if (e is CancellationException) {
                    logcat(LogPriority.WARN) { "备份恢复被取消" }
                } else {
                    logcat(LogPriority.ERROR, e) { "备份恢复期间发生错误" }
                    notifier.showRestoreError(e.message)
                }
                return false
            }
        }

        fun stop(context: Context) {
            context.workManager.cancelUniqueWork(TAG)
        }
    }
}

private const val TAG = "BackupRestore"

private const val LOCATION_URI_KEY = "location_uri" // String
private const val SYNC_KEY = "sync" // Boolean
private const val OPTIONS_KEY = "options" // BooleanArray
