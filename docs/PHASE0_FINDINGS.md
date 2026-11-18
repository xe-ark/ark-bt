# Faz 0 Bulguları (klee, Android 17 / SDK 37, LDAC: SOUNDPEATS Space Pro)

Ham dump'lar: `docs/device_dumps/` (bt_manager.txt, audio_flinger.txt, audio.txt, audio_policy.txt, props.txt)

## 1. BT codec durumu (dumpsys bluetooth_manager)
Format (eski regex GEÇERSİZ):
`mCodecConfig: {codecName:LDAC,mCodecType:4,mSampleRate:0x8(96000),mBitsPerSample:0x4(32),mChannelMode:0x2(STEREO),...}`
Ek satır: `A2DP LDAC State: Config: Rate=96000 Bits=32 Mode=STEREO` (parse etmesi kolay).
- Bitmask rate: 0x1=44100, 0x2=48000, 0x4=88200, 0x8=96000, 0x10=176400, 0x20=192000
- Bitmask bits: 0x1=16, 0x2=24, 0x4=32
- Bu kulaklık (selectable LDAC): rate 0xb = 44100|48000|96000 (**88200 yok**), bits 16|24|32
- Yani hedef rate, selectable kesişimine snap edilmeli (88.2k -> 96k veya 44.1k).
- Xiaomi/vendor codec tipleri var: LHDC V5=15, LHDC_V3=13, LHDC_V2=12 (local caps; V5 192k destekli). Sabit: SBC=0 AAC=1 aptX=2 aptX-HD=3 LDAC=4.
- Şu an codec 96k/32'de, yani sistem varsayılanı zaten yüksek; stream'e göre otomatik inmiyor.
  48k/24 default için `setCodecConfigPreference` ile açıkça ayarlamak gerekecek.

## 2. Audio policy / HAL
- A2DP cihaz profili (dumpsys audio): PCM16 44.1/48/88.2/96k, PCM32 96k, FLOAT 96k. Policy zaten HAL'den dinamik; XML düzenlemesi gereksiz/etkisiz (README'deki mixPort ekleme çıkarılabilir).
- AudioFlinger çıktısı tablo formatında (`Output thread ... Sample rate: 48000 Hz`, `HAL format: AUDIO_FORMAT_PCM_32_BIT`, Track tablosu: `Active Client Session ... Format Chn mask SRate`). Eski `mActive:` parser'ı geçersiz.
- Mixer thread'leri 48000 Hz / PCM32 (BitPerfect sütunu mevcut: bit-perfect desteği var).
- `dumpsys audio`: `Current output device` satırı yok; cihaz `type:0x80 (bt_a2dp)` olarak `Connected devices` altında.
- Bluetooth latency modes HAL tarafında desteklenmiyor (bilgi).

## 3. Oynatma izleme
`dumpsys audio` içinde `onPlaybackConfigChanged: AudioPlaybackConfiguration ... u/pid:10282/1554 state:started FormatInfo{channelMask=0x3, sampleRate=48000}`
-> Faz 0 risk #1 CEVAPLANDI: FormatInfo sampleRate aktif track'te dolu geliyor (başlangıçta 0, started sonrası gerçek). Bu AudioTrack rate'idir; uygulama kaynak rate'ini yeniden örneklediyse gerçek dosya rate'i değildir (Poweramp hi-res çıkışta genelde dosya rate'i kullanır, doğrulanmalı).

## 4. Mimari etkisi
- TrackFormatProbe: ana yol = AudioPlaybackConfiguration (hidden getFormat); yedek = `dumpsys audio` FormatInfo satırı.
- CodecCaps: selectable listesini `BluetoothA2dp.getCodecStatus().getCodecsSelectableCapabilities()` ile oku; sabit snap listesi kullanma.
- LHDC desteği: vendor codec tipleri (12/13/15) A2dpController'da tanınmalı.
- Faz 0 kalan: risk #2 (BLUETOOTH_PRIVILEGED), #3 (SELinux) -> Faz 4'te debug APK ile test.
