# SingleBud

Tek kulaklık kullananlar için: telefonun stereo sesini monoya çevirir, böylece sol ve sağ kanaldaki her şey tek kulaklıktan duyulur.

## Nasıl çalışır

SingleBud sesi kendisi işlemez. Android'in sistem genelindeki mono ses ayarını (`Settings.System` içindeki `master_mono`) açıp kapatır. Bu sayede:

- Tüm uygulamalarda çalışır (Spotify, YouTube, aramalar…)
- Gecikme ve ek pil tüketimi yoktur

Özellikler:

- Ana ekranda mono ses anahtarı
- Hızlı Ayarlar kutucuğu (tek dokunuşla aç/kapat)
- Otomatik mod: kulaklık bağlanınca mono, çıkarılınca tekrar stereo

## Kurulum

Android, sıradan uygulamaların bu ayarı değiştirmesine izin vermez. Bu yüzden iki APK üretilir; birini seç:

| APK | Nasıl kurulur | Nasıl yetki alır |
| --- | --- | --- |
| `SingleBud.apk` | Telefondan normal kurulum | [Shizuku](https://shizuku.rikka.app/) (bilgisayarsız, Android 11+) veya root |
| `SingleBud-legacy.apk` | Android 14+ için bir kez bilgisayardan: `adb install --bypass-low-target-sdk-block SingleBud-legacy.apk` (Android 13 ve altında normal kurulur) | Ek kurulum gerekmez |

İki APK aynı uygulamadır ve aynı anahtarla imzalanır; yalnızca hedef API seviyesi farklıdır (legacy sürüm API 22'yi hedefler, Android de bu sayede ayarı doğrudan yazmasına izin verir).

Shizuku ile: Shizuku'yu kur, kablosuz hata ayıklama ile başlat, SingleBud'da "Shizuku izni ver" düğmesine bas. Telefon yeniden başladığında Shizuku'yu tekrar başlatmak gerekir.

## Derleme

Her `main` gönderiminde GitHub Actions iki APK'yı derler ve **Releases → latest** altına koyar.

Yerelde:

```
gradle assembleStandardDebug assembleLegacyDebug
```

Gereken: JDK 17, Gradle 8.11+, Android SDK 35.

`keystore/debug.keystore` bilerek depoda durur (parola `android`). Gizli bir anahtar değildir; her derlemenin öncekinin üzerine kurulabilmesini sağlar.
