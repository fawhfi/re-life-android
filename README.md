# Re-Life Android 容器

這個目錄是 Re-Life FastAPI + 原生 JavaScript Web App 的 Android WebView 容器。Android 專案內已提交一份版本化 Web 快照到 `app/src/main/assets/web`，CI 不需要相鄰的 `../rel` 倉庫；首次開啟或沒有網路時仍能顯示完整的既有 Web UI。線上頁面的 `/static/*` 會先取得伺服器目前版本（保留 `?v=` 版本參數）；網路失敗、HTTP 錯誤，或 CSS/JS 回傳錯誤 MIME（例如 HTML）時才由 APK 快照回退，避免裸 HTML，同時避免新 HTML 混載舊版 JS/CSS。

Android 層不另建一套主畫面或導航；使用者看到的仍是原有 Web UI。更新網站後，如需把新版 UI 帶入下一個 APK，應明確更新 `app/src/main/assets/web/templates` 與 `app/src/main/assets/web/static` 的快照並重新建置。

## 建置

先安裝 Android SDK Platform 36.1、Build Tools 36.x，以及 JDK 17 或更新版本：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

預設伺服器是 canonical origin `https://www.relifeapp.com`，避免裸網域重新導向時被 WebView 當成外部網站而開啟系統瀏覽器。本機開發可覆蓋：

```powershell
.\gradlew.bat -PREL_SERVER_URL=http://10.0.2.2:8000 assembleDebug
```

產物位於 `app/build/outputs/apk/debug/app-debug.apk`。正式版本建議在 CI 注入正式 HTTPS 網址與簽署設定；不要把測試端點或憑證寫死在原始碼。

如果未配置 Play Cloud project number、challenge endpoint，或客戶端無法取得 token，Android 仍允許離線瀏覽與低風險資料同步，但獎勵兌換/證明交換會在送出前回傳 `403 INTEGRITY_REQUIRED`。若客戶端已取得 token、伺服器卻沒有解碼及驗證，Android 無法偵測這項後端漏驗；因此伺服器驗證仍是正式上線前置，不能由客戶端降級替代。

## APK 完整性與 Root 威脅

`AppIntegrity` 會在每次 Android API 請求附上 package、版本、簽名憑證 SHA-256 與 APK SHA-256。後端可用 `REL_ANDROID_CERT_SHA256`、`REL_ANDROID_APK_SHA256` 建立正式版本 allow-list，偵測重新打包、錯誤簽章、舊版或非官方 APK。建置 release 後可用以下方式取得 APK 雜湊：

```powershell
Get-FileHash .\app\build\outputs\apk\release\app-release.apk -Algorithm SHA256
```

這不是 Root-proof：Root、Frida、Magisk 或修改後的 runtime 可以 Hook 回報值。因此 `/api/rewards/redeem` 與 `/api/rewards/prove-swap` 的真正權威仍是伺服器資料庫與交易邏輯。Android 也支援 Google Play Integrity Standard API；正式建置必須設定 `REL_PLAY_CLOUD_PROJECT_NUMBER` 與伺服器簽發的一次性 `REL_PLAY_CHALLENGE_URL`，伺服器解碼 token 後才可把 verdict 當成信任訊號。完整部署契約見 `docs/play-integrity-server.md`。

## 離線行為

- `GET /api/users/me`、`/api/records`、新聞、天氣、事實、附近回收點與 Agent 對話清單會在成功回應後，以 Keystore AES-GCM 加密保存；離線時只回傳相同網址的最後快取。
- 新增紀錄、更新個人資料、刪除紀錄等明確列入 allow-list 的變更會持久化到加密佇列。包含掃描相片的 data URL 會隨紀錄保存，恢復網路後由 JobScheduler 以同一個 Session Cookie 重送；伺服器目前沒有 request-id 去重，因此同步屬於 at-least-once，使用者不得把兌換獎勵、登入或 Agent 對話離線排隊。
- Android bridge 會把 `/api/users/me` 的 PATCH 再次限制為 `photo_url`/`photoUrl`；`spent_points`、`earned_points`、`claimed_coupons` 等餘額欄位即使被修改也不會送出。這會讓目前 `rel` 中「由瀏覽器保存積分」的示範流程不再持久化；要正式支援獎勵，必須先把積分/優惠券改成伺服器端 ledger 與 Play Integrity 保護的交易 API。
- 登出時清理本機快取與待同步佇列，避免 Session Cookie 或上一位使用者的資料被重放。
- 快取鍵另外包含目前 Session Cookie 的 SHA-256 命名空间，避免同一支手機切換帳號時誤讀另一位使用者的快取；登出仍會清除所有本機快取與佇列。
- 佇列大小受單筆 3 MB 限制；超過限制會回到網頁原本的錯誤流程，避免無界增長。

## 手機端 Agent 沙箱

主畫面的 ReAgent 輸入 `/device permissions` 可開啟 Android Agent 權限對話框；所有 capability 預設關閉，使用者可逐項開啟或撤銷。輸入 `/device {JSON}` 時，才會呼叫已獲授權的本機工具，例如：

```text
/device {"tool":"write_text","path":"notes/today.txt","text":"整理回收清單"}
/device {"tool":"read_text","path":"notes/today.txt"}
/device {"tool":"current_location"}
/device {"tool":"take_photo"}
/device {"tool":"share_text","text":"今天的回收清單"}
/device {"tool":"open_url","url":"https://www.relifeapp.com"}
```

工具只在 App 私有的 `files/sandbox` 目錄執行，不提供 shell、通訊錄、其他 App 檔案、麥克風或背景位置追蹤。讀檔、寫檔、刪檔、裝置資訊、定位、相機、系統分享與開啟 HTTPS 連結分別受獨立 capability 控制；未授權時回傳 `CAPABILITY_DENIED`。定位與相機還需要 Android 系統權限，且定位、相機、分享與外部連結每次執行前都會再次要求使用者確認。確認後的成功或拒絕結果會以 request-id 回送到現有 ReAgent 對話；伺服器 Agent 的 `get_user_location` approval 也會經過同一個原生 `LOCATION` capability。相機照片只保存到 App 私有沙箱。所有呼叫只記錄時間、工具名稱和結果類別，不記錄檔案內容。

## 安全注意事項

- `AndroidManifest.xml` 預設拒絕明文流量，只有 `localhost`、`127.0.0.1`、`10.0.2.2` 開發網域例外；正式環境必須使用 HTTPS。
- WebView 只開啟 JavaScript、DOM Storage 與相機/位置的必要權限，停用 file/content access；外部網域會交給系統瀏覽器。
- 請在後端增加 request-id/idempotency-key（例如以佇列 entry id 作為 `X-Re-Life-Request-Id`），再把高價值 POST 加入離線 allow-list。
- Android WebView 的離線快取是 UX 與資料可用性層，不是伺服器資料庫；帳號、權限、獎勵與 Agent 安全策略仍由 `rel` 後端決定。
