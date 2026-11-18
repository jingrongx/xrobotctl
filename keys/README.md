# 平台签名密钥（不入库）

`keys/` 下的私钥和证书已加入 `.gitignore`，不会提交到仓库。

中枢需要 `com.ubtrobot.permission.ROBOT`（signature 级），必须用与 ROM 一致的平台 key 签名。小方头用的是 AOSP platform test-keys，从 AOSP 源码取：

- `build/target/product/security/platform.pk8`
- `build/target/product/security/platform.x509.pem`

放到本目录后运行 `tools/build_hub.sh`。

**如果用的是自编译 ROM 或其他机器，请换成对应 ROM 的 platform key**——签名不匹配会拿不到权限，HubProvider 里底层 SDK 调用会失败。
