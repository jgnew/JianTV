# JianTV — 极简 TV

无广告的 Android TV / Google TV 桌面启动器。纯 Java，minSdk 26，不申请 INTERNET 权限。

这是 [CleanYourTV](https://github.com/Gathin23/cleanyourtv) launcher（MIT）的品牌化版本：
显示名改成 **JianTV**，图标与横幅换成 JJianTV 标记，功能逻辑保持上游原样。

## 特性

- Apple TV 风格：底部圆角 dock、玻璃高亮、聚焦方框放大并显示应用名
- 无推荐流、无广告位、无遥测；应用列表只读本机已安装的启动项
- 顶栏胶囊：输入源 / Wi-Fi / 设置；左上问候语、时钟与日期
- 可调：每行列数、dock 行数、dock 应用与顺序、隐藏应用、壁纸、辉光取色、动画与时钟格式
- 配置入口：`app/src/main/res/values/config.xml`（改完重新构建即可，无需改 Java）

## 构建

需要 JDK 17 与 Android SDK 34。

```powershell
$env:JAVA_HOME  = "C:\path\to\jdk-17"
$env:ANDROID_HOME = "C:\path\to\android-sdk-34"
.\gradlew.bat assembleRelease testReleaseUnitTest lintRelease --no-daemon
```

产物：`app/build/outputs/apk/release/app-release.apk`，release 构建 APK 约 398 KB。
本地构建前在根目录建 `local.properties` 写 `sdk.dir=<SDK 路径>`（已被 .gitignore 排除）。

可选构建参数：

| 参数 | 作用 |
|---|---|
| `-PlauncherApplicationId=com.ts.launcher` | 换包名。小米固件的 HOME 白名单只认 `com.ts.launcher`，其他品牌看 `docs/devices/` 里的机型说明 |
| `-PlauncherLocales=en,zh,zh-rCN,zh-rTW` | 只保留指定语言的字符串，能省下十来 KB |

## 安装到电视

```powershell
adb connect <电视IP>:5555
adb push app-release.apk /data/local/tmp/tvhome.apk
adb shell pm install -r /data/local/tmp/tvhome.apk
adb shell appops set com.ts.launcher GET_USAGE_STATS allow
adb shell rm -f /data/local/tmp/tvhome.apk
adb shell am force-stop com.ts.launcher
adb shell input keyevent KEYCODE_HOME
```

`GET_USAGE_STATS` 用来判断应用最后一次打开时间，缺了它「最近打开」那行不显示，其余功能正常。
首次按 Home 时系统会弹「用哪个桌面」，选 JianTV 并勾选「始终」。

## 图标与横幅

- 图标：`app/src/main/res/mipmap-xhdpi/ic_launcher.png`，192×192，圆角底板 + 黑色 J + 橙色回声 J + 底部橙弧
- 横幅：`app/src/main/res/drawable-nodpi/banner.png`，320×180，同款标记，无边框无文字

品牌字标写作 **JJianTV**（两个 J 表示「极简 TV」）。应用显示名在
`app/src/main/res/values*/strings.xml` 的 `app_name`。

## 许可

MIT License，原始版权归 Gathin（见 `LICENSE`）。本仓库是在其 launcher 基础上改品牌资源与显示名，未改业务逻辑。
