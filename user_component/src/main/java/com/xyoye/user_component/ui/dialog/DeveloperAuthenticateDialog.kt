package com.xyoye.user_component.ui.dialog

import android.app.Activity
import com.xyoye.common_component.base.BaseActivity
import com.xyoye.common_component.config.DevelopConfig
import com.xyoye.common_component.config.AppConfig
import com.xyoye.common_component.extension.startUrlActivity
import com.xyoye.common_component.network.repository.UserRepository
import com.xyoye.common_component.utils.log.AppLog
import com.xyoye.common_component.utils.SupervisorScope
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.common_component.weight.dialog.BaseBottomDialog
import com.xyoye.user_component.R
import com.xyoye.user_component.databinding.DialogDeveloperAuthenticateBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 *    author: xyoye1997@outlook.com
 *    time  : 2025/1/22
 *    desc  :
 */

class DeveloperAuthenticateDialog(
    private val activity: Activity,
    private val onAuthenticate: () -> Unit
) : BaseBottomDialog<DialogDeveloperAuthenticateBinding>(activity) {

    companion object {
        private const val TAG = "DeveloperAuthenticate"
    }

    private lateinit var binding: DialogDeveloperAuthenticateBinding

    override fun getChildLayoutId(): Int {
        return R.layout.dialog_developer_authenticate
    }

    override fun initView(binding: DialogDeveloperAuthenticateBinding) {
        this.binding = binding

        setTitle("开发者认证")

        binding.inputAppId.setText(DevelopConfig.getAppId())
        binding.inputAppSecret.setText(DevelopConfig.getAppSecret())

        setNegativeText("忽略")
        setNegativeListener {
            dismiss()
        }

        setPositiveListener {
            checkAuthenticate()
        }

        binding.tvReadDocument.setOnClickListener {
            activity.startUrlActivity("https://doc.dandanplay.com/open/")
        }
    }

    /**
     * 开发者认证
     */
    private fun checkAuthenticate() {
        val appId = binding.inputAppId.text.toString()
        val appSecret = binding.inputAppSecret.text.toString()

        if (appId.isEmpty() || appSecret.isEmpty()) {
            ToastCenter.showWarning("请输入AppId和AppSecret")
            return
        }

        //备用服务器会把请求改写到自定义域名，排查认证失败时需要知道该开关的状态
        AppLog.i(
            TAG,
            "开始校验凭证: appId=$appId, 备用服务器=${AppConfig.isBackupDomainEnable()}" +
                "(${AppConfig.getBackupDomain()})"
        )

        SupervisorScope.IO.launch {
            loading(true)
            val result = UserRepository.checkAuthenticate(appId, appSecret)
            loading(false)

            val response = result.getOrNull()
            when {
                response?.code() == 200 -> {
                    AppLog.i(TAG, "凭证校验成功")
                    authenticateSuccess(appId, appSecret)
                }

                response != null -> {
                    val code = response.code()
                    AppLog.e(TAG, "凭证校验失败: HTTP $code")
                    if (code == 403) {
                        ToastCenter.showError("认证失败：凭证无效或未被允许访问（HTTP 403）")
                    } else {
                        ToastCenter.showError("认证失败：服务器返回 HTTP $code")
                    }
                }

                else -> {
                    val error = result.exceptionOrNull()
                    AppLog.e(TAG, "凭证校验请求未送达服务器", error)
                    ToastCenter.showError(
                        "认证失败：请求未送达服务器（${error?.message ?: "未知错误"}）"
                    )
                }
            }
        }
    }

    /**
     * 显示/隐藏加载框
     */
    private suspend fun loading(show: Boolean) {
        if (activity is BaseActivity<*, *>) {
            withContext(Dispatchers.Main) {
                if (show) {
                    activity.showLoading()
                } else {
                    activity.hideLoading()
                }
            }
        }
    }

    /**
     * 认证成功
     */
    private fun authenticateSuccess(appId: String, appSecret: String) {
        DevelopConfig.putAppId(appId)
        DevelopConfig.putAppSecret(appSecret)
        ToastCenter.showSuccess("认证成功")
        onAuthenticate.invoke()
        dismiss()
    }
}
