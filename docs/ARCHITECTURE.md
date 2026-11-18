# Dynamic BT - ROM-native mimari (HyperOS 4 / Android 17, klee)

## Karar
Magisk/KSU + native daemon + a2dp_codecs.conf yerine ROM'a gomulu **priv-app** (system_ext/priv-app/DynamicBt).
Neden: stock'ta a2dp_codecs.conf yok (BT stack = com.android.bt.capex), BT audio HAL AIDL, rate'i
BluetoothA2dp.setCodecConfigPreference() (BLUETOOTH_PRIVILEGED) belirler.

## Akis
AudioPlaybackConfiguration (uid, aktif) -> PlaybackMonitor -> RatePolicy(whitelist, caps, debounce)
-> A2dpController.setCodecConfigPreference -> dogrula (getCodecStatus) -> status UI

## Katmanlar
core/    DynamicBtService, BootReceiver
audio/   PlaybackMonitor, TrackFormatProbe
bt/      A2dpController, CodecCaps, UsbController
policy/  RatePolicy (saf, test edilebilir), WhitelistStore
ui/      MainActivity (Compose)
rom_overlay/system_ext/  priv-app/DynamicBt/, etc/permissions/privapp-permissions-dynamicbt.xml, etc/sysconfig/

## Acik riskler (cihazda dogrula)
1. Gercek track rate'ini API ile almak: AudioPlaybackConfiguration.getFormat() hidden; fallback gerekli.
2. BLUETOOTH_PRIVILEGED icin APK platform imzali ya da privapp allowlist yeterli mi (signature|privileged).
3. SELinux: priv-app domain'i (priv_app) BT servise setCodecConfigPreference cagrisi - denial var mi (logcat).
4. Karsi cihaz LDAC/LC3 selectable rate listesi (CodecCaps) - yalniz desteklenen rate secilir.
5. direct_pcm mixPort EKLENMEZ (bootloop riski); USB XML profilleri korunur.
6. ROM'a ekleme: system_ext.img icine (MIO-KITCHEN), dosya_contexts/erofs repack, vbmeta.
