<div align="center">

# P.U.L.S.E.

### 負載與系統效率效能調校工具 (Performance Utility for Load and System Efficiency)

*專為遊戲掌機打造的免 Root 效能調校神器 —— 賦予掌機智慧大腦。*

[English](README.md) | **台灣繁體中文**

![GitHub 下載量 (所有版本)](https://img.shields.io/github/downloads/keiretrogaming/pulse/total)
![GitHub Release](https://img.shields.io/github/v/release/keiretrogaming/pulse)
![Android](https://img.shields.io/badge/Android-12%2B-3DDC84?logo=android&logoColor=white)
![免 Root](https://img.shields.io/badge/root-%E5%85%8D%20Root-brightgreen)
![授權條款](https://img.shields.io/badge/%E6%8E%88%E6%AC%8A%E6%A2%9D%E6%AC%BE-GPL%20v2.0-blue)

<img src="docs/screenshots/hero.png" alt="PULSE 主畫面" width="720">

</div>

---

## 什麼是 PULSE？

玩掌機時，我們常常面臨兩個看似互斥的渴望：**穩定流暢的高畫面幀率**，以及**持久耐用的電池續航（外加不會狂嘯尖叫的風扇）**。原廠韌體通常只給予幾個生硬的「效能模式」便草草了事。但我想要精細微調每一個參數旋鈕 —— 並且需要一個夠聰明的系統，能在遊戲運行時依據負載即時為我自動調控。

因此，**PULSE** 誕生了。

PULSE 是一款專為各叢集設計的 CPU/GPU 調校工具，具備能在維持目標畫面幀率的同時將功耗壓至最低的閉迴路 **AutoTDP** 控制器、閉迴路**自訂風扇**溫控、即時數據遙測 **HUD / 遊戲內 OSD**，以及搖桿 **RGB** 燈效控制 —— 最重要的是，**這一切完全無需 Magisk，也無需授予 Root 權限**。PULSE 直接透過裝置內建的原生 `PServer` 系統服務來寫入受保護的 sysfs 節點，這項免 Root 技術源自 ClusterTune 的開創性設計（請參閱[開源授權與誌謝](#開源授權與誌謝)）。

若您的掌機內建該服務，PULSE 即可隨開即用；若裝置不支援，PULSE 會直接提示不相容，絕不會要求您進行任何危險的 Root 操作。

## 支援裝置

| 裝置 | SoC 處理器 | `ro.soc.model` | GPU 繪圖核心 | Android 版本 |
| --- | --- | --- | --- | --- |
| **AYN Odin 3** | Qualcomm Dragonwing Q8 | `CQ8725S` | Adreno 830 | 15 |
| **AYN Thor** (Base / Pro / Max) | Snapdragon 8 Gen 2 | `QCS8550` | Adreno 740 | 13 |
| **Retroid Pocket 6** | Snapdragon 8 Gen 2 | `QCS8550` | Adreno 740 | 13 |

> PULSE 會在執行階段即時從 sysfs 讀取裝置的 CPU 叢集、各核心時脈範圍與 GPU 效能等級，自動彈性適應硬體架構。其他內建 `PServerBinder` 服務的 AYN 與 Retroid 掌機亦有可能支援 —— 但上述三款是經過深度調校與實機完整測試的裝置。

> [!WARNING]
> **PULSE 會變更 CPU 與 GPU 的時脈限制。** 這會影響系統穩定性、散熱溫度、電池壽命與效能表現，**無法保證對所有硬體絕對安全。** 請在充分理解時脈限制機制並自行承擔風險的前提下使用。裝置底層原生的 Linux 核心溫控節流機制依舊在運作，但您手中握有掌控時脈旋鈕的主導權。

---

## 核心功能

### AutoTDP —— 智慧大腦

這是 PULSE 最強大的核心特色。**AutoTDP 是一套閉迴路控制器，能即時自動動態調節 CPU 與 GPU 時脈，以最低的功耗穩定維持目標畫面幀率。** 只需選定遊戲並設定目標（例如 60 FPS），剩餘的交給 AutoTDP 即可 —— 畫面順暢時立即縮減時脈省電，遇到複雜場景瞬間補足頻率，並在運行過程中持續自我學習該遊戲的最佳時脈底線。

AutoTDP 具備能在能耗效率與極致流暢之間平衡的三種偏好模式，可全域或針對個別應用程式設定：

| 模式 | 優先考量 | 在 Odin 3 上的最高功耗上限限制為... |
| --- | --- | --- |
| **省電 (Efficient)** | 極致低耗能、最安靜、最涼爽 | 約 11 W |
| **平衡 (Balanced)** | 能耗與效能的均衡點 | 約 12.5 W |
| **極速流暢 (Smooth)** | 極致幀率穩定、維持較高時脈 | 約 14 W |

上述瓦數數值為**最高上限，而非固定目標**。AutoTDP 永遠優先使用可滿足當前畫面幀率的最低功耗 —— 運行輕量遊戲時功耗會遠低於此上限；只有當遊戲真正產生龐大負載時才會逐步拉升，且絕不突破上限（避免掌機小巧機身因盲目衝幀而過熱降頻）。

<details>
<summary><b>使用指南：發揮 AutoTDP 的最大潛能</b></summary>

<br>

<div align="center"><img src="docs/screenshots/autotdp.png" alt="AutoTDP 設定面板" width="640"></div>

- **開啟開關、設定目標畫面幀率、開始暢玩。** 無需為每個應用程式個別設定，AutoTDP 即會自動成為所有前景遊戲的預設調節器。
- **依情境挑選合適模式：** 長時間續航或模擬器請選擇*省電*；對畫面幀率要求極高的重度遊戲請選擇*極速流暢*；日常遊玩則推薦*平衡*。
- **個別遊戲設定優先。** 為特定遊戲單獨指定模式或目標幀率後，PULSE 會自動記憶並於進入該遊戲時立即套用。
- **客觀理解硬體物理限制：** 在小型掌機上執行真正的重度 3A 大作時，終究受限於硬體極限 —— 若 CPU 某核心已經滿載且無法再提升時脈，任何調校工具都無法憑空變出 60 FPS。AutoTDP 的價值在於以最低發熱與最高效率維持當前硬體能達到的最高流暢度，避免高溫、暴轉噪音與頻繁降頻卡頓。當偵測到目標幀率無法達成時，它會立即停止無效燒機耗電。
- **風扇搭配秘訣：** 建議搭配下方的**自訂風扇**功能。讓 AutoTDP 透過動態調節時脈控制溫度，調校好的風扇曲線則安靜處理剩餘廢熱。

</details>

### 自訂風扇控制（閉迴路）

PULSE 透過單一 **鎖定目標溫度 (Hold Target Temp)** 開關，提供兩種驅動風扇控制器的方式：

- **鎖定目標溫度 (Hold Target Temp - 智慧溫控)** —— 採用 PI 比例積分控制器將 SoC 核心溫度精確維持在您設定的數值，並僅使用達成該目標所需的最低風扇轉速。能安靜時絕不吵雜，需要散熱時全力輸出。
- **手動風扇曲線 (Manual curve)** —— 具備可拖曳節點的溫度-風扇轉速樣條曲線（類似 EVGA 風格），提供「偏向涼爽 / 偏向安靜」全域偏移滑桿，並支援「自動校準」功能，能自動探測並學習您掌機風扇的真實工作轉速區間。

風扇控制在 Android 系統桌面、遊戲內與 AutoTDP 運行期間皆持續運作，絕不會在您切換出遊戲時突然跳回吵雜的原廠風扇設定檔。

<details>
<summary><b>使用指南：風扇調校技巧</b></summary>

<br>

<div align="center"><img src="docs/screenshots/fan.png" alt="自訂風扇面板" width="640"></div>

- **多數使用者推薦開啟「鎖定目標溫度」。** 設定合適的溫度目標（例如 78-80 °C），後續完全交由 PI 控制器自動維持，兼顧極致靜音與穩定壓溫。
- **風扇目標溫度應等於或略高於 AutoTDP 的舒適溫區。** 當 AutoTDP 藉由時脈降溫時，稍高的風扇目標能讓風扇保持低轉速甚至怠速，達到最安靜的效果。
- **偏好手動繪製曲線？** 關閉「鎖定目標溫度」，手動拖曳曲線節點，並可隨時透過「偏向涼爽 / 偏向安靜」滑桿平移整條曲線。建議初次使用先執行一次「自動校準」，使曲線精確對應風扇的實際轉速範圍。
- **散熱物理現實：** 在極限重載下，晶片在風扇 100% 全速運轉時仍可能達到 88-94 °C。沒有任何風扇曲線能在晶片極致發熱時保持靜音，但一條優良的曲線能保證在其他所有時刻皆寂靜無聲。

</details>

### 手動進階控制 —— 時脈、效能檔位與功耗目標

當您希望完全手動掌控掌機效能時：

- **各叢集 CPU 時脈上限** —— 針對個別 cpufreq policy 獨立滑桿調整，數值完美貼合硬體真實支援的可用頻率。
- **GPU 頻率上限** —— 透過 Adreno 效能等級索引 (Power-level index) 進行精確限頻（這是 GPU 調節器實際遵從的底層控制項）。
- **CPU 與 GPU 最低頻率下限 (Floors)** —— 將任一側的最低時脈維持在指定比例以上，換取更穩定的畫面幀生成時間 (Frame Pacing)。
- **超大核 (Prime Core) 升頻限制** —— 僅限制超大核心叢集的最高頻率，不影響其他核心效能的同時大幅抑制作業發熱。
- **四檔效能設定 (Power Tiers)** —— 3A極限 (AAA/Max)、平衡 (Balanced)、省電 (Power Saving) 與自訂 (Custom)。Snapdragon 處理器無硬體可編程瓦數上限，因此各檔位皆是藉由 CPU + GPU 頻率組合與裝置實際 OPP 頻率表所構築的最佳化功耗包絡線。
- **功耗目標 (Power Target)** —— 仿照 PC TDP 概念的單一主控滑桿，能依比例同步縮放所有 CPU 叢集與 GPU 上限（亦可切換為僅調控 CPU）。
- **顯示設定** —— 渲染解析度縮放 (降低渲染解析度以釋放 GPU 餘裕，且完全可逆) 與螢幕更新頻率切換。

### 即時數據遙測 —— HUD 與遊戲內浮動 OSD

即時監控 CPU/GPU 時脈、GPU 負載率、電池狀態、CPU/GPU 核心溫度、即時功耗以及具備自我校準功能的峰值功耗估算值 —— 所有數據皆以冷色至熱色的色彩漸層直觀呈現。開啟 **OSD 浮動面板** 後，效能數據（加上即時 **FPS 畫面幀率** 與平滑演算法計算的**剩餘可用電量時間**）將無縫懸浮顯示於任何遊戲之上。

<div align="center"><img src="docs/screenshots/hud.png" alt="即時遙測 HUD" width="640"></div>

### 搖桿 RGB 燈效

支援三種模式控制類比搖桿光圈 LED：
- **電池資訊燈 (Battery)** —— 微光指示目前電量。
- **溫度指示燈 (Heat)** —— 依據 SoC 處理器溫度動態變換色彩。
- **自訂手動模式 (Manual)** —— 獨立調整左右搖桿色彩與亮度。

<div align="center"><img src="docs/screenshots/rgb.png" alt="搖桿 RGB 調色盤" width="640"></div>

### 設定檔管理與自動化

支援設定檔的儲存、編輯、刪除、排序、匯入與匯出。具備開機自動套用、休眠感知智慧切換，以及支援系統快捷設定方塊 (Quick Settings Tile)，可直接切換效能檔位、開啟快速微調對話框或直接啟動應用程式。

### 五款精美動態主題

**Signal (訊號 - 預設)**、**Crimson (深紅)**、**Cyberpunk (賽博龐克)**、**Ronin (浪人)** 與 **Ad Astra (星際遨遊)** —— 全數採用 OLED 友善的極黑底色，每款主題皆由純 Jetpack Compose Canvas 程序化動態繪製（無著色器、無點陣圖資源，極致輕量）。

<details>
<summary>各主題視覺風格預覽</summary>

<br>

<div align="center"><img src="docs/screenshots/themes.png" alt="PULSE 主題風格" width="640"></div>

- **Signal / Crimson** —— 漂浮星座遙測粒子場與脈動光環擴散。
- **Cyberpunk** —— 霓虹透視網格搭配 CRT 復古掃描線效果。
- **Ronin** —— 水墨風格日式意境：朱紅圓月、水墨筆觸與落葉飄散動態。
- **Ad Astra** —— 傾斜黑洞吸積盤，其色溫隨當前效能檔位動態變化（閒置時呈冷冽冰藍，拉至極限檔位時燃燒為琥珀烈焰）。

</details>

---

## 安裝與初次使用

### 方式一：手動安裝 APK

1. 至 [Releases](https://github.com/keiretrogaming/pulse/releases) 頁面下載最新版 APK 並完成安裝（可能需在系統中允許「安裝未知來源應用程式」）。
2. 初次啟動時授予以下兩項必要權限：
   - **使用情況存取權限 (Usage Access)** —— 讓 PULSE 能辨識當前前景遊戲（用於個別遊戲設定檔自動切換與 AutoTDP 調節）。
   - **顯示於其他應用程式上層 (Display over other apps)** —— 供 OSD 浮動面板懸浮於遊戲畫面上顯示。
3. **完成設定 —— 全程絕不跳出任何 Root 授權請求。** 若裝置缺少內建 `PServerBinder` 系統服務，PULSE 將主動提示裝置不相容。

系統要求：Android 12+ (`minSdk 31`)。

### 方式二：ADB 一鍵自動部署（開發與快速測試）

本專案提供專屬自動部署指令碼，支援本機偵測、授權檢查、APK 自動推播安裝與啟動：

- **Windows 使用者**：直接點擊執行根目錄下的 `deploy.bat`，或在 PowerShell 中執行：
  ```powershell
  .\deploy.ps1
  ```
- 指令碼將自動：
  1. 檢查 ADB 連線狀態與裝置授權。
  2. 優先搜尋本地編譯之 APK，若無則自動自 GitHub Actions 抓取最新產物。
  3. 自動安裝/升級至掌機裝置，並即時啟動 PULSE 繁體中文版！

### 快速上手指南（新手 5 分鐘教學）

1. 開啟 PULSE —— 主畫面 HUD 即時顯示當前 CPU/GPU 時脈、溫度與耗電功率。
2. 開啟 **AutoTDP**，將目標畫面幀率設為 **60**，模式維持於 **省電 (Efficient)**。
3. （選用）將風扇切換至 **自訂 (Custom) -> 鎖定目標溫度 (Hold Target Temp)** 並將目標設為約 80 °C。
4. 啟動遊戲。PULSE 將在背景自動為您調校最合適的效能時脈。
5. 若遊玩重度 3A 遊戲希望獲取極致順暢度，可將該遊戲的 AutoTDP 模式切換至 **極速流暢 (Smooth)**。

---

## 設定選項一覽

<details>
<summary><b>AutoTDP 自動調頻設定</b></summary>

- **啟用 AutoTDP** —— 開啟閉迴路控制器，作為前景遊戲的預設自動調節器。
- **目標 FPS** —— 欲維持的畫面幀率（選項取決於裝置螢幕更新頻率）。
- **能耗模式** —— 省電 (Efficient) / 平衡 (Balanced) / 極速流暢 (Smooth)（代表瓦數上限與響應激進程度）。各模式的持續功耗上限標示於晶片圖示上。
- **個別應用程式設定** —— 為特定遊戲指定專屬模式或目標幀率，個別遊戲設定永遠優先於全域設定。

</details>

<details>
<summary><b>風扇設定</b></summary>

- **風扇模式** —— 靜音 (Silent) / 智慧 (Smart) / 極限效能 (Sport)（原廠預設模式）或 **自訂 (Custom)**（由 PULSE 深度接管）。
- **鎖定目標溫度** —— 開啟 = 使用 PI 控制器自動維持指定溫度；關閉 = 套用自訂樣條曲線。
- **目標溫度** —— PI 控制器鎖定的目標溫度值。
- **曲線編輯器** —— 手動拖曳曲線節點；「偏向涼爽 / 偏向安靜」調整整條曲線偏移；「自動校準」自動測試並學習風扇的工作轉速區間。
- **響應速度** —— 風扇轉速調整的激進程度。

</details>

<details>
<summary><b>手動效能設定</b></summary>

- **效能檔位** —— 3A極限 (AAA/Max)、平衡 (Balanced)、省電 (Power Saving)、自訂 (Custom)。
- **功耗目標** —— 主控滑桿，等比例同步調節所有 CPU 叢集與 GPU 上限（可切換為僅調控 CPU）。
- **各 CPU 叢集上限 / GPU 上限 / 頻率下限 / 超大核限制** —— 提供細緻的手動控制項。
- **調頻器 (Governor)** —— Performance (效能) / Balanced (平衡) / Power Save (省電)。
- **渲染解析度縮放 / 更新頻率** —— 顯示層級微調。

</details>

<details>
<summary><b>懸浮資訊面板 (OSD) 設定</b></summary>

- **啟用懸浮面板** —— 在遊戲畫面上懸浮顯示即時數據（需授予「顯示於其他應用程式上層」權限）。
- **顯示元件** —— 自由勾選欲顯示的資訊（FPS 畫面幀率、時脈頻率、核心溫度、即時功耗、剩餘可用時間、遊戲計時器等）。
- OSD 僅會在遊戲中自動浮現，當返回 PULSE 本體、系統桌面或 Android 系統介面時會自動智慧隱藏。

</details>

<details>
<summary><b>RGB、設定檔與自動化</b></summary>

- **RGB 燈效模式** —— 電池資訊燈 / 溫度指示燈 / 手動獨立調整顏色與亮度。
- **設定檔管理** —— 儲存 / 編輯 / 刪除 / 重新排序 / 匯入 / 匯出。
- **自動化設定** —— 開機自動套用、休眠自動切換、快捷設定方塊行為自訂。
- **主題風格** —— Signal (訊號) / Crimson (深紅) / Cyberpunk (賽博龐克) / Ronin (浪人) / Ad Astra (星際遨遊)。

</details>

---

## 免 Root 運作原理

這些掌機的原廠韌體中內建了特權系統服務 (`PServerBinder`)。PULSE 透過 Java 反射技術取得該服務介面，並以 Root 權限執行簡短的 shell 指令碼直接操作 sysfs 節點 —— 全程無需 Magisk、無需解鎖 Bootloader、更無需使用者授予 Root 權限：

```sh
# CPU 叢集 —— 將指定頻率 (kHz) 寫入 scaling_max_freq
chmod 666 /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq
echo 2745600 > /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq   # 單位：kHz
chmod 444 /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq

# GPU —— 透過 Adreno 效能等級索引 (0 為最高頻率) 進行限制，而非直接設定頻率。
# 必須先將 min_pwrlevel 放寬至最慢等級，否則 Linux 核心會將上限彈回。
chmod 666 /sys/class/kgsl/kgsl-3d0/min_pwrlevel
echo 13 > /sys/class/kgsl/kgsl-3d0/min_pwrlevel
chmod 444 /sys/class/kgsl/kgsl-3d0/min_pwrlevel
chmod 666 /sys/class/kgsl/kgsl-3d0/max_pwrlevel
echo 6 > /sys/class/kgsl/kgsl-3d0/max_pwrlevel                # 效能等級索引 (此處對應約 660 MHz)
chmod 444 /sys/class/kgsl/kgsl-3d0/max_pwrlevel
```

若裝置中缺乏 `PServerBinder` 服務，應用程式會安全降級並明確回報裝置不相容 —— 絕不會引導或要求使用者 Root 裝置。

> **單位說明：** cpufreq 節點採用以 kHz 為單位的頻率；kgsl GPU 限制則採用效能等級索引 (0 = 最快)，而非直接寫入頻率。PULSE 內部統一以 kHz 運算各頻率，並在寫入節點前自動轉換為對應的等級索引。

---

## 常見問題與疑難排解

<details>
<summary><b>PULSE 提示我的裝置不相容？</b></summary>

代表您裝置的原廠韌體未包含 `PServerBinder` 系統服務，PULSE 無法在免 Root 的條件下存取底層受保護節點。除了對裝置進行 Root 之外別無他法，但 PULSE 基於安全性考量，原則上絕不涉及 Root 相關操作。

</details>

<details>
<summary><b>AutoTDP 在重度遊戲中無法維持 60 FPS？</b></summary>

若 CPU 某個核心已處於滿載狀態且處理器已達最高時脈上限，這是硬體本身的物理極限 —— 任何調校工具都無法突破硬體極限。AutoTDP 會精準偵測此狀態，立即停止無效燒機以避免虛耗電量與發熱，並以最涼爽且高效的功耗維持硬體所能達成的最佳幀率。若希望在更高發熱/功耗的代價下換取極限畫面流暢度，可嘗試切換至 **極速流暢 (Smooth)** 模式。

</details>

<details>
<summary><b>高負載遊戲時風扇運轉聲音較大？</b></summary>

在極限高負載下，行動晶片發熱量極大，而散熱必須仰賴空氣對流 —— 這是物理定律。建議使用 **自訂 (Custom) -> 鎖定目標溫度 (Hold Target Temp)** 並設定合理的目標溫度，搭配 **省電 (Efficient)** AutoTDP 模式，即可在硬體散熱允許的範圍內獲得最安靜的遊戲體驗。

</details>

<details>
<summary><b>OSD 浮動面板沒有顯示？</b></summary>

請確認已於系統設定中授予 **「顯示於其他應用程式上層」** 與 **「具有使用量存取權的應用程式」** 兩項權限，並確認在 PULSE 設定中已開啟懸浮面板開關。請注意，為了避免干擾，OSD 面板在 PULSE 主程式內部、系統桌面與 Android 系統設定介面會自動隱藏。

</details>

---

## 從原始碼編譯

```bash
./gradlew testDebugUnitTest assembleDebug
```

編譯產出的除錯版 APK 將位於 `app/build/outputs/apk/debug/`。簽署設定將自動讀取自環境變數 `ANDROID_KEYSTORE_*` 或 `local.properties`；若未提供，則使用預設 Debug 金鑰進行簽署。

---

## 開發背景與 AI 輔助說明

PULSE 專案的開發獲得了先進人工智慧編程助理的深度協作支援。專案的大量原始碼、README 與文件，皆是在維護者的架構指導、程式碼審查與實機驗證下由 AI 輔助撰寫完成。每一項功能變更與架構調整均經過本機編譯、單元測試並在真實掌機硬體上通過完整驗證。

此公開說明旨在秉持開源社群透明公開的原則 —— 當您評估、參與貢獻或建立 Fork 分支時，能完整理解專案的開發背景與歷程。

---

## 開源授權與誌謝

本專案採用 **GNU General Public License v2.0 (GPL-2.0)** 條款開放授權 —— 詳見 [LICENSE](LICENSE)。

PULSE 所採用的免 Root `PServer` 通訊架構技術源自 **ClusterTune** 之開創性成就；完整誌謝清單與第三方元件授權告示請參閱 [NOTICE.md](NOTICE.md)。在任何衍生專案、Fork 分支或重新散布版本中，請務必完整保留上述誌謝與版權宣告（此為 GPL 授權之法定強制要求）。

---

<div align="center">

*專為掌機玩家社群用心打造。若 PULSE 讓您的掌機更安靜、更涼爽、續航更持久，這正是本專案存在的全部意義。*

<!-- 追溯索引標籤：[MOD-20260920-09] -->

</div>
