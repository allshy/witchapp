# WitchApp

这个仓库同时保留两个 Android 程序：

| 模块 | App | 包名 | 作用 |
|---|---|---|---|
| `app` | OV Watch Sync | `com.ovwatch.sync` | 原来的手环数据同步客户端，发送 `OV+SEND` 并解析 `OVDATA` |
| `debug-console` | WitchApp Debug | `com.allshy.witchapp` | 新增的 SPP 调试台，替代蓝牙串口助手做 HR/ST/CFG 测试 |

## Debug Console 功能

- 已配对蓝牙设备下拉选择，连接后自动发送 `OV` 验证。
- 发送命令时自动追加 `\r\n`。
- 一键发送 HR/ST/ALL/OFF 调试流命令。
- 一键执行 TEST_PLAN 里的静息、阅读、慢走、快走、恢复、100 步、500 步、误判测试场景。
- 支持 MARK、REF HR、REF STEP、自定义命令、`OV+CFG=` 参数热改。
- 实时解析 `$HR` / `$ST`，显示 BPM、锁定、运动评分、总步数、步频、walking 状态。
- 保存 RX 原始日志为 `ovwatch_YYYYMMDD_HHMMSS.txt`，可继续交给 `decode_debug_log.py` 分析。

## 构建

```bash
gradle :app:assembleDebug :debug-console:assembleDebug
```

GitHub Actions 会同时上传两个 APK：

- `ov-watch-sync-debug-apk`
- `witchapp-debug-console-apk`
