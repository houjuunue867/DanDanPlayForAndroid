package com.xyoye.common_component.base.app

import android.app.Application
import android.content.Context
import android.os.Handler
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.alibaba.android.arouter.launcher.ARouter
import com.tencent.bugly.crashreport.CrashReport
import com.tencent.mmkv.MMKV
import com.xyoye.common_component.BuildConfig
import com.xyoye.common_component.notification.Notifications
import com.xyoye.common_component.config.DevelopConfig
import com.xyoye.common_component.utils.ActivityHelper
import com.xyoye.common_component.utils.SecurityHelper
import com.xyoye.common_component.utils.aliyun.EMASHelper
import com.xyoye.common_component.utils.log.AppLog
import com.xyoye.open_cc.OpenCCFile

/**
 * Created by xyoye on 2020/4/13.
 */

open class BaseApplication : Application(), ImageLoaderFactory {
    companion object {

        private var APPLICATION_CONTEXT: Application? = null
        private var mMainHandler: Handler? = null

        fun getAppContext(): Context {
            return APPLICATION_CONTEXT!!
        }

        fun getMainHandler(): Handler {
            return mMainHandler!!
        }
    }

    override fun onCreate() {
        super.onCreate()

        APPLICATION_CONTEXT = this
        mMainHandler = Handler(getAppContext().mainLooper)

        if (BuildConfig.DEBUG) {
            ARouter.openLog()
            ARouter.openDebug()
        }
        MMKV.initialize(this)
        ARouter.init(this)
        CrashReport.initCrashReport(
            this,
            SecurityHelper.getInstance().buglyId,
            BuildConfig.DEBUG
        )
        Notifications.setupNotificationChannels(this)
        ActivityHelper.instance.init(this)
        EMASHelper.init(this)
        //初始化日志文件（崩溃、播放、SMB 代理等日志都会写入文件，方便排查问题）
        AppLog.init(this)
        initDevelopCertificate()
        OpenCCFile.init(this)
    }

    /**
     * 非官方签名的应用需要自行配置弹弹play开放平台的凭证。
     * 若打包时在 gradle/assemble/developer.properties 内置了凭证，则首次启动写入配置。
     */
    private fun initDevelopCertificate() {
        try {
            if (BuildConfig.DANDAN_APP_ID.isNotEmpty() && DevelopConfig.getAppId().isNullOrEmpty()) {
                DevelopConfig.putAppId(BuildConfig.DANDAN_APP_ID)
            }
            if (BuildConfig.DANDAN_APP_SECRET.isNotEmpty() && DevelopConfig.getAppSecret().isNullOrEmpty()) {
                DevelopConfig.putAppSecret(BuildConfig.DANDAN_APP_SECRET)
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .build()
    }
}
