package com.xyoye.user_component.ui.fragment

import android.net.Uri
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import androidx.core.content.FileProvider
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import com.alibaba.android.arouter.launcher.ARouter
import com.xyoye.common_component.config.AppConfig
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.extension.addToClipboard
import com.xyoye.common_component.network.config.Api
import com.xyoye.common_component.utils.AppUtils
import com.xyoye.common_component.utils.log.AppLog
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.common_component.weight.dialog.CommonDialog
import com.xyoye.user_component.R
import java.io.File

/**
 * Created by xyoye on 2021/2/23.
 */

class AppSettingFragment : PreferenceFragmentCompat() {

    companion object {
        private const val TAG = "AppSettingFragment"

        fun newInstance() = AppSettingFragment()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.preferenceDataStore = AppSettingDataStore()
        addPreferencesFromResource(R.xml.preference_app_setting)
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val backupDomainAddress = findPreference<EditTextPreference>("backup_domain_address")

        findPreference<Preference>("dark_mode")?.apply {
            setOnPreferenceClickListener {
                ARouter.getInstance()
                    .build(RouteTable.User.SwitchTheme)
                    .navigation()
                return@setOnPreferenceClickListener true
            }
        }

        findPreference<Preference>("export_log")?.apply {
            val logPath = AppLog.logFilePath()
            summary = if (logPath.isNullOrEmpty()) "日志文件不可用" else logPath
            setOnPreferenceClickListener {
                showLogExportDialog()
                return@setOnPreferenceClickListener true
            }
        }

        findPreference<Preference>("app_version")?.apply {
            summary = AppUtils.getVersionName()
            setOnPreferenceClickListener {
                AppUtils.checkUpdate()
                return@setOnPreferenceClickListener true
            }
        }

        findPreference<Preference>("license")?.apply {
            setOnPreferenceClickListener {
                ARouter.getInstance()
                    .build(RouteTable.User.License)
                    .navigation()
                return@setOnPreferenceClickListener true
            }
        }

        findPreference<Preference>("about_us")?.apply {
            setOnPreferenceClickListener {
                ARouter.getInstance()
                    .build(RouteTable.User.AboutUs)
                    .navigation()
                return@setOnPreferenceClickListener true
            }
        }

        findPreference<SwitchPreference>("backup_domain_enable")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                backupDomainAddress?.isVisible = newValue as Boolean
                return@setOnPreferenceChangeListener true
            }
            backupDomainAddress?.isVisible = isChecked
        }

        backupDomainAddress?.apply {
            summary = AppConfig.getBackupDomain()
            setOnPreferenceChangeListener { _, newValue ->
                val newAddress = newValue as String
                if (checkDomainUrl(newAddress)) {
                    summary = newAddress
                    return@setOnPreferenceChangeListener true
                }
                return@setOnPreferenceChangeListener false
            }
        }

        super.onViewCreated(view, savedInstanceState)
    }

    /**
     * 查看/导出运行日志
     */
    private fun showLogExportDialog() {
        val logPath = AppLog.logFilePath()
        if (logPath.isNullOrEmpty()) {
            ToastCenter.showError("日志文件不可用，请先正常使用一段时间")
            return
        }

        CommonDialog.Builder(requireActivity()).run {
            tips = "导出运行日志"
            content = "日志文件：\n$logPath\n\n可以把日志文件发送给开发者用于分析问题。"
            addNegative("复制路径") { dialog ->
                logPath.addToClipboard()
                ToastCenter.showSuccess("日志路径已复制")
                dialog.dismiss()
            }
            addPositive("分享日志") { dialog ->
                dialog.dismiss()
                shareLogFile(logPath)
            }
            build()
        }.show()
    }

    private fun shareLogFile(logPath: String) {
        try {
            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.log.provider",
                File(logPath)
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "弹弹play 运行日志")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "分享日志"))
        } catch (e: Exception) {
            AppLog.e(TAG, "分享日志失败，改为复制日志路径", e)
            logPath.addToClipboard()
            ToastCenter.showSuccess("无法直接分享，日志路径已复制")
        }
    }

    private fun checkDomainUrl(url: String): Boolean {
        if (TextUtils.isEmpty(url)) {
            ToastCenter.showError("地址保存失败，地址为空")
            return false
        }
        val uri = Uri.parse(url)
        if (TextUtils.isEmpty(uri.scheme)) {
            ToastCenter.showError("地址保存失败，协议错误")
            return false
        }
        if (TextUtils.isEmpty(uri.host)) {
            ToastCenter.showError("地址保存失败，域名错误")
            return false
        }
        if (uri.port == -1) {
            ToastCenter.showError("地址保存失败，端口错误")
            return false
        }
        return true
    }


    inner class AppSettingDataStore : PreferenceDataStore() {
        override fun getBoolean(key: String?, defValue: Boolean): Boolean {
            return when (key) {
                "hide_file" -> AppConfig.isShowHiddenFile()
                "splash_page" -> AppConfig.isShowSplashAnimation()
                "backup_domain_enable" -> AppConfig.isBackupDomainEnable()
                else -> super.getBoolean(key, defValue)
            }
        }

        override fun putBoolean(key: String?, value: Boolean) {
            when (key) {
                "hide_file" -> AppConfig.putShowHiddenFile(value)
                "splash_page" -> AppConfig.putShowSplashAnimation(value)
                "backup_domain_enable" -> AppConfig.putBackupDomainEnable(value)
            }
        }

        override fun getString(key: String?, defValue: String?): String? {
            return when (key) {
                "backup_domain_address" -> AppConfig.getBackupDomain()
                else -> super.getString(key, defValue)
            }
        }

        override fun putString(key: String?, value: String?) {
            when (key) {
                "backup_domain_address" -> AppConfig.putBackupDomain(value ?: Api.DAN_DAN_SPARE)
                else -> super.putString(key, value)
            }
        }
    }
}
