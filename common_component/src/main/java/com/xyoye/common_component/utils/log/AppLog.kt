package com.xyoye.common_component.utils.log

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 日志落盘工具
 *
 * 日志会写入外部存储的公共目录（默认 /sdcard/DanDanPlay/logs），
 * 这样用文件管理器或数据线就能直接把日志拷出来；
 * 若外部存储不可用，则退回应用私有目录 Android/data/包名/files/logs。
 *
 * 每次启动 App 会新建一个日志文件，最多保留 10 个。
 */
object AppLog {

    private const val TAG = "AppLog"
    private const val LOG_DIR_NAME = "DanDanPlay/logs"
    private const val LOG_FILE_PREFIX = "dandanplay_"
    private const val LOG_FILE_SUFFIX = ".log"
    private const val MAX_LOG_FILE_COUNT = 10
    private const val PROBE_FILE_NAME = ".write_probe"

    private val lock = Any()
    private val initialized = AtomicBoolean(false)

    //日志写入线程，避免在播放/网络线程上直接做文件 IO
    private val writeExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AppLogWriter")
    }

    @Volatile
    private var logDirectory: File? = null

    @Volatile
    private var writer: BufferedWriter? = null

    /**
     * 初始化日志文件，建议在 Application.onCreate() 中调用，越早越好
     */
    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) {
            return
        }
        try {
            val directory = createLogDirectory(context)
            if (directory == null) {
                return
            }
            logDirectory = directory
            clearOldLogs(directory)

            val logFile = File(directory, generateLogFileName())
            writer = BufferedWriter(FileWriter(logFile, true))
            writeHeader(context, logFile)

            installCrashHandler()
        } catch (e: Throwable) {
            //日志初始化失败不能影响 App 运行
            e.printStackTrace()
        }
    }

    /**
     * 当前日志文件路径，未初始化成功时返回 null
     */
    fun logFilePath(): String? {
        val directory = logDirectory ?: return null
        return directory.listFiles { file -> file.isFile && file.name.endsWith(LOG_FILE_SUFFIX) }
            ?.maxByOrNull { it.lastModified() }
            ?.absolutePath
    }

    fun d(tag: String, message: String) {
        write("D", tag, message, null)
    }

    fun i(tag: String, message: String) {
        write("I", tag, message, null)
    }

    fun w(tag: String, message: String) {
        write("W", tag, message, null)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        write("E", tag, message, throwable)
    }

    fun flush() {
        if (writer == null) {
            return
        }
        try {
            writeExecutor.execute {
                try {
                    synchronized(lock) {
                        writer?.flush()
                    }
                } catch (e: Throwable) {
                    //忽略
                }
            }
        } catch (e: Throwable) {
            //忽略
        }
    }

    /**
     * 等待队列中的日志全部写入完成（崩溃时使用）
     */
    fun flushNow(timeoutMs: Long = 1000L) {
        if (writer == null) {
            return
        }
        try {
            writeExecutor.submit(Runnable {
                try {
                    synchronized(lock) {
                        writer?.flush()
                    }
                } catch (e: Throwable) {
                    //忽略
                }
            }).get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Throwable) {
            //忽略
        }
    }

    private fun write(level: String, tag: String, message: String, throwable: Throwable?) {
        if (writer == null) {
            return
        }
        val threadName = Thread.currentThread().name
        val exceptionText = if (throwable != null) stackTraceOf(throwable) else null
        try {
            writeExecutor.execute {
                val currentWriter = writer ?: return@execute
                try {
                    synchronized(lock) {
                        val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
                        currentWriter.write(time)
                        currentWriter.write(" [")
                        currentWriter.write(threadName)
                        currentWriter.write("] ")
                        currentWriter.write(level)
                        currentWriter.write("/")
                        currentWriter.write(tag)
                        currentWriter.write(": ")
                        currentWriter.write(message)
                        currentWriter.newLine()
                        if (exceptionText != null) {
                            currentWriter.write(exceptionText)
                            currentWriter.newLine()
                        }
                        currentWriter.flush()
                    }
                } catch (e: Throwable) {
                    //忽略写日志自身的异常
                }
            }
        } catch (e: Throwable) {
            //忽略
        }
    }

    private fun stackTraceOf(throwable: Throwable): String {
        val stringWriter = StringWriter()
        val printWriter = PrintWriter(stringWriter)
        try {
            throwable.printStackTrace(printWriter)
        } finally {
            printWriter.close()
        }
        return stringWriter.toString()
    }

    private fun generateLogFileName(): String {
        val time = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return "$LOG_FILE_PREFIX$time$LOG_FILE_SUFFIX"
    }

    private fun createLogDirectory(context: Context): File? {
        //优先使用外部存储公共目录，方便直接取出日志
        if (Environment.MEDIA_MOUNTED == Environment.getExternalStorageState()) {
            val publicDirectory = File(Environment.getExternalStorageDirectory(), LOG_DIR_NAME)
            if (ensureDirectory(publicDirectory)) {
                return publicDirectory
            }
        }

        //退回到应用私有目录
        val externalDirectory = context.getExternalFilesDir("logs")
        if (externalDirectory != null && ensureDirectory(externalDirectory)) {
            return externalDirectory
        }

        val innerDirectory = File(context.filesDir, "logs")
        if (ensureDirectory(innerDirectory)) {
            return innerDirectory
        }
        return null
    }

    private fun ensureDirectory(directory: File): Boolean {
        return try {
            if (!directory.exists() && !directory.mkdirs()) {
                return false
            }
            val probeFile = File(directory, PROBE_FILE_NAME)
            probeFile.writeText("")
            probeFile.delete()
            true
        } catch (e: Throwable) {
            false
        }
    }

    private fun clearOldLogs(directory: File) {
        try {
            val logFiles = directory.listFiles { file ->
                file.isFile && file.name.startsWith(LOG_FILE_PREFIX) && file.name.endsWith(LOG_FILE_SUFFIX)
            } ?: return
            if (logFiles.size <= MAX_LOG_FILE_COUNT) {
                return
            }
            logFiles.sortedBy { it.lastModified() }
                .take(logFiles.size - MAX_LOG_FILE_COUNT)
                .forEach { it.delete() }
        } catch (e: Throwable) {
            //忽略
        }
    }

    private fun writeHeader(context: Context, logFile: File) {
        i(TAG, "================== 日志开始 ==================")
        i(TAG, "日志文件: ${logFile.absolutePath}")
        i(TAG, "应用版本: ${appVersion(context)}")
        i(
            TAG,
            "设备信息: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}(API ${Build.VERSION.SDK_INT})"
        )
        i(TAG, "CPU架构: ${Build.SUPPORTED_ABIS.joinToString()}")
        i(TAG, "=============================================")
    }

    private fun appVersion(context: Context): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            "${packageInfo.versionName}"
        } catch (e: Throwable) {
            "unknown"
        }
    }

    private fun installCrashHandler() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            //崩溃信息一定要落盘，且必须在进程结束前刷新
            e("Crash", "线程[${thread.name}]发生未捕获异常", throwable)
            flushNow()
            previousHandler?.uncaughtException(thread, throwable)
        }
    }
}
