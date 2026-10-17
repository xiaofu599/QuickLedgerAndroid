# 随手记账 Android App

原生 Android 项目（Kotlin + Android SDK），不是网页套壳。功能包括：

- 收入/支出录入、分类、日期、备注
- 本月收入、支出、结余
- 本月按日支出柱状图
- 月度预算及超支提示
- 账单搜索、月份筛选、收入/支出筛选
- JSON 备份导出与恢复（Android 文件选择器）
- PIN 密码锁；应用离开前台后再次解锁
- 账单在本机使用 Android Keystore AES-GCM 加密保存

## 用 Android Studio 构建 APK

1. 安装 Android Studio（建议当前稳定版），首次启动时安装 Android SDK Platform 35 和 Android Build Tools。
2. 解压本项目 ZIP。
3. Android Studio 选择 **Open**，打开 `QuickLedgerAndroid` 文件夹。
4. 等待 Gradle Sync 完成。首次构建需要联网下载 Android Gradle Plugin/Kotlin 插件。
5. 选择菜单 **Build → Build Bundle(s) / APK(s) → Build APK(s)**。
6. APK 通常生成在 `app/build/outputs/apk/debug/app-debug.apk`。
7. 将 APK 传到 Android 手机，允许该来源安装应用后打开安装。

也可以在项目根目录运行（需先安装 Android SDK 并配置 `local.properties` 或 `ANDROID_HOME`）：
`gradle assembleDebug`

## 使用说明

- 首次打开先设置 4–8 位数字 PIN；以后启动/从后台返回时需要解锁。
- 长按某条账单可删除。
- 导出备份为 JSON；备份文件是可读文本，不包含应用内的加密封装，请妥善保存。
- 恢复备份会替换当前账单和预算，应用会在导入前提示。
- PIN 遗忘后没有在线找回机制；卸载或清除应用数据可能导致本地账单无法恢复，建议定期导出备份。
- 目前为本地单机应用，无账号、无云同步。

## 当前环境限制

此交付环境没有安装 Android SDK 或 Gradle，因此不能在这里直接编译并验证 APK。项目源码已生成；需要用 Android Studio 首次同步依赖并构建 APK。
