package com.xyoye.common_component.storage.file.helper

import com.xyoye.common_component.storage.Storage
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.common_component.storage.file.impl.SmbStorageFile
import com.xyoye.common_component.storage.impl.SmbStorage
import com.xyoye.common_component.utils.IOUtils
import com.xyoye.common_component.utils.RangeUtils
import com.xyoye.common_component.utils.getFileExtension
import com.xyoye.common_component.utils.log.AppLog
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder
import kotlin.random.Random

/**
 * Created by xyoye on 2023/1/15.
 */

class SmbPlayServer private constructor() : NanoHTTPD(randomPort()) {

    private var mStorageFile: SmbStorageFile? = null
    private var mStorage: SmbStorage? = null
    private var mContentType: String = "video/*"
    private var mInputStream: InputStream? = null

    private val resourceNotFound by lazy {
        resourceNotFoundResponse()
    }
    private val resourceOpenFailed by lazy {
        resourceOpenFailedResponse()
    }

    private object Holder {
        val instance = SmbPlayServer()
    }

    companion object {

        private const val LOG_TAG = "SmbPlayServer"

        //随机端口
        private fun randomPort() = Random.nextInt(20000, 30000)

        @JvmStatic
        fun getInstance() = Holder.instance
    }

    override fun serve(session: IHTTPSession): Response {
        val storage = mStorage ?: return resourceNotFound
        val storageFile = mStorageFile ?: return resourceNotFound

        val rangeText = session.headers["range"]
        AppLog.i(
            LOG_TAG,
            "收到请求: method=${session.method}, uri=${session.uri}, range=$rangeText, " +
                "文件=${storageFile.fileName()}, 文件长度=${storageFile.fileLength()}"
        )

        //每个请求都使用独立的数据流：不能再去关闭上一个请求的流，
        //否则会把另一个连接正在读取的流关掉，播放器就会拿到不完整数据。
        //每个响应发送完毕后，由 NanoHTTPD 自动关闭它自己的数据流。

        //重新打开数据流，重要，否则无法设置offset
        val inputStream = getInputStream(storage, storageFile)
        if (inputStream == null) {
            AppLog.e(LOG_TAG, "打开 SMB 文件失败: ${storageFile.filePath()}")
            return resourceOpenFailed
        }
        //使用计数流，记录本次请求实际发送了多少字节
        val countStream = CountingInputStream(inputStream, storageFile.fileName())
        mInputStream = countStream

        //解析Range
        val rangeArray = rangeText?.run {
            RangeUtils.getRange(this, storageFile.fileLength())
        }

        //存在range，且contentLength != 0
        return if (rangeArray != null && rangeArray[2] != 0L) {
            getPartialResponse(countStream, rangeArray, storageFile.fileLength())
        } else {
            getOKResponse(countStream)
        }
    }

    private fun getInputStream(storage: Storage, file: StorageFile): InputStream? {
        return try {
            runBlocking {
                storage.openFile(file)
            }
        } catch (e: Exception) {
            AppLog.e(LOG_TAG, "打开 SMB 数据流异常: ${file.filePath()}", e)
            null
        }
    }

    private fun getPartialResponse(
        inputStream: InputStream,
        rangeArray: Array<Long>,
        sourceLength: Long
    ): Response {
        val start = rangeArray[0]
        val end = rangeArray[1]
        //本次响应真正要发送的字节数。
        //注意：这里曾经错用了整个文件长度（rangeArray[2]），使 Content-Length 多于实际可发送的
        //字节数，播放器读到“数据不足”（读超时 / 提前 EOF）就会中断播放。
        val responseLength = end - start + 1

        //设置Offset
        skipFully(inputStream, start)

        AppLog.i(LOG_TAG, "返回 206: Range=$start-$end, Content-Length=$responseLength")

        //响应内容：长度必须是 responseLength，NanoHTTPD 会据此输出 Content-Length
        return newFixedLengthResponse(
            Response.Status.PARTIAL_CONTENT,
            mContentType,
            inputStream,
            responseLength
        ).apply {
            //添加响应头（不要再手写 Content-Length，否则会出现重复响应头）
            addHeader("Accept-Ranges", "bytes")
            addHeader("Content-Range", "bytes $start-$end/$sourceLength")
        }
    }

    /**
     * 跳过指定字节数。SMB 数据流的 skip 可能一次跳不完，需要循环补齐，
     * 否则响应体会从错误的偏移开始。
     */
    private fun skipFully(inputStream: InputStream, byteCount: Long) {
        if (byteCount <= 0) {
            return
        }
        var remaining = byteCount
        try {
            while (remaining > 0) {
                val skipped = inputStream.skip(remaining)
                if (skipped > 0) {
                    remaining -= skipped
                } else if (inputStream.read() < 0) {
                    //已经到文件末尾
                    break
                } else {
                    //skip 返回 0 时退化为读取丢弃，避免死循环
                    remaining--
                }
            }
        } catch (e: IOException) {
            AppLog.e(LOG_TAG, "跳过 SMB 数据流失败: 需要跳过=$byteCount, 剩余=$remaining", e)
        }
    }

    private fun getOKResponse(inputStream: InputStream): Response {
        AppLog.i(LOG_TAG, "返回 200(chunked)，无 Range 请求")
        return newChunkedResponse(
            Response.Status.OK,
            mContentType,
            inputStream
        )
    }

    private fun resourceNotFoundResponse(): Response {
        AppLog.e(LOG_TAG, "返回 404: 播放资源为空")
        return newFixedLengthResponse(
            Response.Status.NOT_FOUND,
            "*/*",
            "resource not found"
        )
    }

    private fun resourceOpenFailedResponse(): Response {
        AppLog.e(LOG_TAG, "返回 500: 打开播放资源失败")
        return newFixedLengthResponse(
            Response.Status.INTERNAL_ERROR,
            "*/*",
            "open resource failed"
        )
    }

    private fun getContentType(filePath: String): String {
        if (filePath.isEmpty()) {
            return "video/*"
        }
        val extension = getFileExtension(filePath)
        return "video/$extension"
    }

    suspend fun startSync(timeoutMs: Long = 5000): Boolean {
        AppLog.i(LOG_TAG, "启动播放服务, 已启动=${wasStarted()}, 端口=$listeningPort")
        if (wasStarted()) {
            release()
        }
        return withTimeout(timeoutMs) {
            start()
            while (isActive) {
                if (wasStarted()) {
                    return@withTimeout true
                }
            }
            stop()
            return@withTimeout false
        }
    }

    fun generatePlayUrl(
        storage: SmbStorage,
        storageFile: SmbStorageFile
    ): String {
        mStorage = storage
        mStorageFile = storageFile
        mContentType = getContentType(storageFile.filePath())
        val encodeFileName = URLEncoder.encode(storageFile.fileName(), "utf-8")
        val playUrl = "http://127.0.0.1:$listeningPort/$encodeFileName"
        AppLog.i(LOG_TAG, "生成播放地址: $playUrl, contentType=$mContentType")
        return playUrl
    }

    fun release() {
        AppLog.w(LOG_TAG, "释放播放服务（会中断正在播放的视频）, 端口=$listeningPort")
        IOUtils.closeIO(mInputStream)
        this@SmbPlayServer.stop()
        AppLog.i(LOG_TAG, "播放服务已停止")
    }

    /**
     * 统计单个请求实际发送的字节数，便于排查播放中断问题
     */
    private class CountingInputStream(
        inputStream: InputStream,
        private val fileName: String
    ) : FilterInputStream(inputStream) {

        private var readCount = 0L
        private var closed = false

        override fun read(): Int {
            val result = super.read()
            if (result >= 0) {
                readCount++
            }
            return result
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val result = super.read(b, off, len)
            if (result > 0) {
                readCount += result
            }
            return result
        }

        override fun close() {
            if (closed) {
                return
            }
            closed = true
            AppLog.i(LOG_TAG, "数据流已关闭: 文件=$fileName, 本次实际发送=$readCount 字节")
            super.close()
        }
    }
}
