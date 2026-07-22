# Re-Life Android 容器

這個目錄是 `../rel` FastAPI + 原生 JavaScript Web App 的 Android WebView 容器。建置時會把目前的 `../rel/static` 與主要頁面複製到 APK 的 assets，因此首次開啟、沒有網路時仍能顯示已打包的 UI；恢復網路後 WebView 會回到 `REL_SERVER_URL` 的同源伺服器。

## 建置

先安裝 Android SDK Platform 36.1、Build Tools 36.x，以及 JDK 17 或更新版本：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

預設伺服器是 `https://relifeapp.com`。本機開發可覆蓋：

```powershell
.\gradlew.bat -PREL_SERVER_URL=http://10.0.2.2:8000 assembleDebug
```

產物位於 `app/build/outputs/apk/debug/app-debug.apk`。正式版本建議在 CI 注入正式 HTTPS 網址與簽署設定；不要把測試端點或憑證寫死在原始碼。

## 離線行為

- `GET /api/users/me`、`/api/records`、新聞、天氣、事實、附近回收點與 Agent 對話清單會在成功回應後，以 Keystore AES-GCM 加密保存；離線時只回傳相同網址的最後快取。
- 新增紀錄、更新個人資料、刪除紀錄等明確列入 allow-list 的變更會持久化到加密佇列。包含掃描相片的 data URL 會隨紀錄保存，恢復網路後由 JobScheduler 以同一個 Session Cookie 重送；伺服器目前沒有 request-id 去重，因此同步屬於 at-least-once，使用者不得把兌換獎勵、登入或 Agent 對話離線排隊。
- 登出時清理本機快取與待同步佇列，避免 Session Cookie 或上一位使用者的資料被重放。
- 佇列大小受單筆 3 MB 限制；超過限制會回到網頁原本的錯誤流程，避免無界增長。

## 手機端 Agent 沙箱

主畫面的 ReAgent 輸入 `/device {JSON}` 時，會直接呼叫本機工具，例如：

```text
/device {"tool":"write_text","path":"notes/today.txt","text":"整理回收清單"}
/device {"tool":"read_text","path":"notes/today.txt"}
```

工具只在 App 私有的 `files/sandbox` 目錄執行，不提供 shell、通訊錄、其他 App 檔案、麥克風或背景位置追蹤。讀檔、寫檔、刪檔、裝置資訊是分開的 capability，預設全拒絕；從 Activity 選單「Agent 權限」逐項授權或撤銷。所有呼叫只記錄時間、工具名稱和結果類別，不記錄檔案內容。

## 安全注意事項

- `AndroidManifest.xml` 預設拒絕明文流量，只有 `localhost`、`127.0.0.1`、`10.0.2.2` 開發網域例外；正式環境必須使用 HTTPS。
- WebView 只開啟 JavaScript、DOM Storage 與相機/位置的必要權限，停用 file/content access；外部網域會交給系統瀏覽器。
- 請在後端增加 request-id/idempotency-key（例如以佇列 entry id 作為 `X-Re-Life-Request-Id`），再把高價值 POST 加入離線 allow-list。
- Android WebView 的離線快取是 UX 與資料可用性層，不是伺服器資料庫；帳號、權限、獎勵與 Agent 安全策略仍由 `rel` 後端決定。
