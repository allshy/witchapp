# WitchApp Debug

这是桌面版 `debug_console.py` 的安卓 SPP 调试台版本，包名为 `com.allshy.witchapp`。它直接连接已配对的 KT6328/KT6368 经典蓝牙串口通道，适合电脑离手环太远、需要手机靠近手环做现场测试的场景。

## 功能

- 已配对蓝牙设备下拉选择，连接后自动发送 `OV` 验证。
- 发送命令时自动追加 `\r\n`。
- 一键发送 HR/ST/ALL/OFF 调试流命令。
- 一键执行 TEST_PLAN 里的静息、阅读、慢走、快走、恢复、100 步、500 步、误判测试场景。
- 支持 MARK、REF HR、REF STEP、自定义命令、`OV+CFG=` 参数热改。
- 实时解析 `$HR` / `$ST`，显示 BPM、锁定、运动评分、总步数、步频、walking 状态。
- 保存 RX 原始日志为 `ovwatch_YYYYMMDD_HHMMSS.txt`，可继续交给 `decode_debug_log.py` 分析。
- 断开连接时会自动在 App 私有 Documents 目录备份 RX/TX 日志。

## 使用

1. 用 Android Studio 打开 `tools/android_debug_console`。
2. 等 Gradle 同步完成后，连接手机，运行 `app`。
3. 手机上先到系统蓝牙设置里配对 KT6328/KT6368 的经典蓝牙/SPP 设备。
4. 回到 App，点“刷新已配对”，选择 KT 设备，点“连接”。
5. 日志里看到自动发出的 `>> OV` 和设备返回 `OK` 后，就可以按测试按钮。

注意：如果蓝牙列表里同时有 SPP 和 BLE，优先连接经典蓝牙/SPP 那个；这个 App 走的是串口透传，不走 BLE GATT。
