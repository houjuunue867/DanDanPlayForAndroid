import setup.applicationSetup
import java.util.Properties
import java.io.File

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("kapt")
}

applicationSetup()
//打包完成后自动把 APK 复制到指定目录（在 gradle/assemble/output.properties 里配置 APK_OUTPUT_DIR，留空则不复制）
val outputProperties = Properties().apply {
    val file = rootProject.file("gradle/assemble/output.properties")
    if (file.exists()) {
        file.inputStream().use { input -> load(input) }
    }
}
val apkOutputDir = outputProperties.getProperty("APK_OUTPUT_DIR", "").trim().trim('"')

if (apkOutputDir.isNotEmpty()) {
    //注意：这里不能用 Gradle 的 Copy 任务（from/into）——它会扫描目标目录，
    //当目标目录里有大量无关文件/媒体（例如 W:\hdd）时会让构建长时间卡住，所以直接做文件复制。
    val outputDirFile = File(apkOutputDir)
    val apkSourceDirs = listOf(
        layout.buildDirectory.dir("outputs/apk/debug").get().asFile,
        layout.buildDirectory.dir("outputs/apk/release").get().asFile
    )
    val copyApkTask = tasks.register("copyApkToOutputDir") {
        group = "build"
        description = "把打包产物复制到 output.properties 中配置的目录"
        outputs.upToDateWhen { false }
        doLast {
            if (!outputDirFile.exists() && !outputDirFile.mkdirs()) {
                logger.lifecycle("跳过 APK 输出：目录不可用 $apkOutputDir")
                return@doLast
            }
            apkSourceDirs.forEach { dir ->
                dir.listFiles { file -> file.isFile && file.name.endsWith(".apk", ignoreCase = true) }
                    ?.forEach { apk ->
                        val target = File(outputDirFile, apk.name)
                        apk.copyTo(target, overwrite = true)
                        logger.lifecycle("APK 已输出: ${target.absolutePath}")
                    }
            }
        }
    }
    tasks.matching { it.name == "assembleDebug" || it.name == "assembleRelease" }
        .configureEach { finalizedBy(copyApkTask) }
}

android {
    namespace = "com.xyoye.dandanplay"
    compileSdk = Versions.compileSdkVersion
    defaultConfig {
        applicationId = Versions.applicationId
        minSdk = Versions.minSdkVersion
        targetSdk = Versions.targetSdkVersion
        targetSdk = Versions.targetSdkVersion
        versionCode = Versions.versionCode
        versionName = Versions.versionName
        multiDexEnabled = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.add("armeabi-v7a")
            abiFilters.add("arm64-v8a")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = true
        }
    }
}

kapt {
    arguments {
        arg("AROUTER_MODULE_NAME", project.name)
    }
}

dependencies {
    implementation(project(":common_component"))
    implementation(project(":player_component"))
    implementation(project(":anime_component"))
    implementation(project(":user_component"))
    implementation(project(":local_component"))
    implementation(project(":storage_component"))

    kapt(Dependencies.Alibaba.arouter_compiler)
}
