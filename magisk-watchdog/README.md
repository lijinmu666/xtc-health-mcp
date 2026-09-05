# Magisk watchdog

这是兜底层，不授予 system UID，不把 APK 塞入 priv-app。

使用前确认正式 APK 的包名和前台服务组件与 `service.sh` 一致。然后把本目录中的 `module.prop`、`service.sh` 放在 ZIP 根目录，压缩为 Magisk 模块并从 Magisk 安装。

安装后重启。日志在模块目录的 `watchdog.log`。卸载模块即可移除 watchdog。

如果 ROM 仍拦截服务，先查看：

```sh
su -c 'dumpsys activity services com.lijinmu.xtchealth'
su -c 'logcat -d | grep -i xtchealth'
```

不要把检查间隔改成几秒，也不要在脚本里持有永久 wakelock。
