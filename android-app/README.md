# zaptun Android app

The device side of zaptun: a `VpnService` that sends every TCP connection on the
device to an HTTP proxy (ZAP). See the [main README](../README.md#how-the-vpn-works)
for how it works.

You don't normally install or start this app yourself. The ZAP add-on bundles
the APK, installs it, and starts it (and so does
[`scripts/zaptun.sh`](../scripts/zaptun.sh), without the add-on) with:

```shell
adb shell am start -n dev.davidjosearaujo.zaptun/.MainActivity \
    --es host 127.0.0.1 --ei port 9090 --es apps '' --ez start true
adb shell am start -n dev.davidjosearaujo.zaptun/.MainActivity --ez stop true
```

The app is a single page: connection status with Disconnect/Connect, whether
ZAP's CA has system and user trust, and three collapsible sections: the
intercepted ports (add or remove), every app, each with a switch to include
or exclude it, and the recent connections (Activity). Connections are logged to
logcat (`adb logcat -s zaptun`). See the
[main README](../README.md#on-the-device) for the details.

Other adb extras: `--es ports '80,443,8080'` replaces the intercepted ports,
`--es ca_sha256 <hex>` tells the app which CA to look for when checking trust, and
`--ez all_ports true` turns on "Also find HTTP on every other port".

Build and test on its own:

```shell
../gradlew -p . :app:assembleDebug :app:testDebugUnitTest
```
