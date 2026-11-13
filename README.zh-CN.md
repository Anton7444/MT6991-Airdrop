[English](README.md) | [繁體中文](README.zh-TW.md) | **简体中文**

# MT6991-Airdrop

为搭载联发科 MT6991（天玑 9400+）的一加 / OPPO 设备带来 AirDrop。通过原厂 Wi-Fi 芯片与 Apple 设备互传文件，附带控制中心开关、系统分享应用里的实时活动卡片、真实的传输进度，以及让普通 Wi-Fi 在 AirDrop 运行时仍能正常使用的协调策略。

| 设备 | 型号 | 状态 |
| --- | --- | --- |
| 一加平板 2 | OPD2508 | 已测试 |
| OPPO Find X8s | PKT110 | 尚未在真机测试 |
| OPPO Find X8s+ | PLB110 | 尚未在真机测试 |

需要 Root（支持 `/data/adb/service.d` 开机脚本）和 LSPosed。目前只用 iPhone 测试过。预编译的内核模块只适用于内核 `6.6.118-android15-8`。

## 工作方式

- 位于 `/data/adb/o13` 的 Root 服务会加载 `wonder` 和 `mtk_wonder_dev` 内核模块、建立 AWDL 接口并运行 `owly` 守护进程。看门狗负责让它保持正常，另一个脚本会在传输时调整发送速率。
- 应用同时也是 LSPosed 模块，作用范围是系统分享应用（`com.coloros.oshare`）。它会在分享面板加入 AirDrop 选项、显示收发的实时活动卡片，并回报真实进度。
- Wi-Fi 协调可以自定义：保持 Wi-Fi 连接并让 AirDrop 使用另一个信道，或在传输期间让 AirDrop 优先。
- 卸载应用就会移除服务：看门狗发现包名消失后，会在 90 秒后自动清理。应用内的“移除服务”按钮则会立即清理。

## 更新

应用每天最多检查一次 GitHub 上的最新版本（可在设置中关闭），有新版本时会在主页和设置页显示提示。这是应用唯一使用的网络连接，不会发送任何个人数据。下载和安装仍需手动进行。

## 安装

1. 安装 APK，并在 LSPosed 中启用模块，作用范围选系统分享应用。
2. 打开应用，在设置页点“安装”。应用会解开内置的无线电包并启动服务。
3. 从应用或控制中心开关打开 AirDrop。

## 编译

```
./gradlew assembleDebug
```

需要 Android SDK 36、JDK 17、Gradle 8.13。`app/src/main/radio/package-mt6991.py` 会用 `app/src/main/radio` 里的脚本重新生成 `app/src/main/assets/radio-mt6991.zip`。

## 目录结构

- `app/src/main/java` – 应用、守护进程客户端和 LSPosed 模块
- `app/src/main/radio` – Root 服务脚本、速率调整工具源码、打包脚本
- `app/src/main/assets/radio-mt6991.zip` – 预编译的无线电包
- `kernel/mtk_wonder_dev` – 平台设备垫片模块的源码

## 许可证

GPL-3.0，详见 `LICENSE`。无线电包内含第三方组件，各自适用其许可证，列于 `NOTICE`。

本软件会以 Root 身份加载内核模块并修改系统设置，使用风险自负。
