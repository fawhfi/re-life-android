# Play Integrity 服务器契约

Android 客户端只负责取得并转发 Play Integrity token；它不能自行决定设备是否可信。正式环境必须由服务器生成一次性 challenge、调用 Google 解码接口，并在执行高价值动作前验证 verdict。

## 客户端配置

构建时注入：

```powershell
.\gradlew.bat `
  -PREL_PLAY_CLOUD_PROJECT_NUMBER=1234567890 `
  -PREL_PLAY_CHALLENGE_URL=https://www.relifeapp.com/api/integrity/challenge `
  assembleRelease
```

`REL_PLAY_CHALLENGE_URL` 必须是 HTTPS（本地开发才允许 `localhost`、`127.0.0.1` 或 `10.0.2.2` 使用 HTTP）。客户端会带上当前 WebView Session Cookie，服务器应将 challenge 绑定到当前用户、动作和短暂过期时间。

## 推荐接口

`GET /api/integrity/challenge`

服务器生成至少 128 位不可预测随机值，保存 `{nonce_hash, user_id, action, expires_at, used=false}`，并返回：

```json
{"nonce":"<base64url>","expires_at":"2026-07-22T12:00:00Z"}
```

客户端使用 Standard API，把 `base64url(SHA-256(nonce + "\n" + action + "\ncom.relife.mobile"))` 作为 `requestHash`，并把 token、`X-Re-Life-Play-Integrity-Nonce`、`X-Re-Life-Play-Integrity-Action` 一起发送到受保护 API。每次奖励/兑换动作都会申请新的 challenge 与 token。服务器必须要求 nonce/action 与数据库中的未使用 challenge 完全匹配，按同一公式重算并核对解码结果的 `requestDetails.requestHash`；验证成功后立即原子地标记 `used=true`，拒绝重复使用或过期 challenge。

## Token 验证

服务器使用 Google Play Integrity decode token API（服务帐号凭据只能放在服务器）并检查：

- `appIntegrity.appRecognitionVerdict == PLAY_RECOGNIZED`；
- package name 等于 `com.relife.mobile`；
- 证书摘要等于正式发布证书；
- `accountDetails.appLicensingVerdict == LICENSED`（Google Play 分发版本）；
- `deviceIntegrity.deviceRecognitionVerdict` 至少包含 `MEETS_DEVICE_INTEGRITY`，奖励、兑换、积分等高价值动作可要求 `MEETS_STRONG_INTEGRITY`；
- token 时间、cloud project number、`requestDetails.requestHash` 与服务器记录一致；
- nonce 与原始 challenge 一致且尚未使用。

验证失败时返回稳定的 `403 INTEGRITY_REQUIRED`，不要把 Google 原始 token 或服务帐号错误泄露给客户端。

## 必须保护的动作

以下动作必须由服务器资料库和交易逻辑决定，不能信任客户端传来的 `points`、`earned_points`、`spent_points`、优惠券或兑换成本：

- `POST /api/rewards/redeem`；
- `POST /api/rewards/prove-swap`（如果已部署）；
- 任何增加积分、发放优惠券或改变账户余额的接口；
- 将离线记录结算为奖励的后台任务。

普通页面、资讯读取和低风险缓存可以不要求 Play Integrity，但仍应验证 Session、CSRF/Origin 和服务器权限。

## 当前 `rel` 后端状态

当前来源 `../rel` 尚未实现上述 challenge、Google decode、verdict 校验或高价值 API 拒绝逻辑。Android 会发送 headers，但在部署独立 verifier/gateway 或把该契约实现进后端之前，不能声称已经阻断 root、重打包 APK 或伪造 token。

离线队列只允许记录、头像资料和删除记录等低风险操作；每个重放请求带有 `X-Re-Life-Request-Id`，服务器若要实现 exactly-once，仍需按该 ID 建立幂等表。
