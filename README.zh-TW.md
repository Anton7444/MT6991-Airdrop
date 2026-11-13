[English](README.md) | **繁體中文** | [简体中文](README.zh-CN.md)

# MT6991-Airdrop

為搭載聯發科 MT6991（天璣 9400+）的一加 / OPPO 裝置帶來 AirDrop。透過原廠 Wi-Fi 晶片與 Apple 裝置互傳檔案，附帶控制中心開關、系統分享程式裡的即時活動卡片、真實的傳輸進度，以及讓一般 Wi-Fi 在 AirDrop 運作時仍能正常使用的協調策略。

| 裝置 | 型號 | 狀態 |
| --- | --- | --- |
| 一加平板 2 | OPD2508 | 已測試 |
| OPPO Find X8s | PKT110 | 尚未在實機測試 |
| OPPO Find X8s+ | PLB110 | 尚未在實機測試 |

需要 Root（支援 `/data/adb/service.d` 開機腳本）與 LSPosed。目前只用 iPhone 測試過。預編譯的核心模組只適用於核心 `6.6.118-android15-8`。

## 運作方式

- 位於 `/data/adb/o13` 的 Root 服務會載入 `wonder` 與 `mtk_wonder_dev` 核心模組、建立 AWDL 介面並執行 `owly` 守護程式。看門狗負責讓它保持正常，另一支腳本會在傳輸時調整傳送速率。
- App 同時也是 LSPosed 模組，作用範圍是系統分享程式（`com.coloros.oshare`）。它會在分享面板加入 AirDrop 選項、顯示收發的即時活動卡片，並回報真實進度。
- Wi-Fi 協調可以自訂：保持 Wi-Fi 連線並讓 AirDrop 使用另一個頻道，或在傳輸期間讓 AirDrop 優先。
- 移除 App 就會移除服務：看門狗發現套件不見後，會在 90 秒後自動清理。App 內的「移除服務」按鈕則會立即清理。

## 安裝

1. 安裝 APK，並在 LSPosed 中啟用模組，作用範圍選系統分享程式。
2. 開啟 App，在設定頁按「安裝」。App 會解開內附的無線電套件並啟動服務。
3. 從 App 或控制中心開關打開 AirDrop。

## 編譯

```
./gradlew assembleDebug
```

需要 Android SDK 36、JDK 17、Gradle 8.13。`app/src/main/radio/package-mt6991.py` 會用 `app/src/main/radio` 裡的腳本重新產生 `app/src/main/assets/radio-mt6991.zip`。

## 目錄結構

- `app/src/main/java` – App、守護程式客戶端與 LSPosed 模組
- `app/src/main/radio` – Root 服務腳本、速率調整工具原始碼、打包腳本
- `app/src/main/assets/radio-mt6991.zip` – 預編譯的無線電套件
- `kernel/mtk_wonder_dev` – 平台裝置墊片模組的原始碼

## 授權

GPL-3.0，詳見 `LICENSE`。無線電套件內含第三方元件，各自適用其授權，列於 `NOTICE`。

本軟體會以 Root 身分載入核心模組並修改系統設定，使用風險自負。
