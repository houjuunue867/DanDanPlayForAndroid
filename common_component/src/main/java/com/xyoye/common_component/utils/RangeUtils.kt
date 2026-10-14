package com.xyoye.common_component.utils

import okhttp3.internal.toLongOrDefault

object RangeUtils {

    fun getRange(rangeText: String, contentLength: Long): Array<Long> {
        val rangArray = Array<Long>(3) { 0 }

        val range = parseRange(rangeText, contentLength)
            ?: return rangArray

        rangArray[0] = range.first
        rangArray[1] = range.second
        rangArray[2] = contentLength
        return rangArray
    }

    /**
     * 解析Range请求头的值
     */
    fun parseRange(rangeValue: String, contentLength: Long): Pair<Long, Long>? {
        if (contentLength <= 0) {
            return null
        }

        val maxRange = contentLength - 1
        val separator = "-"
        val header = "bytes="
        val range = rangeValue.replace(header, "")

        // e.g. "" or "-"
        if (range.length < 2) {
            return null
        }
        // e.g. "abc"
        val separatorIndex = range.indexOf(separator)
        if (separatorIndex == -1) {
            return null
        }
        // e.g. "-499"
        if (separatorIndex == 0) {
            //后缀区间：表示“最后 N 字节”
            val suffixLength = range.substring(1).toLongOrDefault(0L)
            if (suffixLength <= 0) {
                return null
            }
            val start = (contentLength - suffixLength).coerceAtLeast(0L)
            return start to maxRange
        }
        // e.g. "500-"
        if (separatorIndex == range.length - 1) {
            val start = range.substring(0, separatorIndex).toLongOrDefault(-1L)
            return if (start < 0 || start > maxRange) {
                null
            } else {
                start to maxRange
            }
        }
        // e.g. "500-999"
        val start = range.substring(0, separatorIndex).toLongOrDefault(-1L)
        val end = range.substring(separatorIndex + 1).toLongOrDefault(-1L)
        if (start < 0 || end < 0 || start > maxRange || start > end) {
            return null
        }
        //结束位置超过文件末尾时按文件末尾截断
        return start to end.coerceAtMost(maxRange)
    }
}
