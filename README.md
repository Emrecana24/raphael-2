# Raphael (v3.0)

Android (native Java) hafif AI sohbet kabuğu. Gemini (`gemini.google.com`) ve AI Studio (`aistudio.google.com`) web arayüzlerini tek oturumlu arka plan WebView içinde çalıştırır.

## Ortam ve AI Geliştirme Kuralları
- Hedef: Samsung Galaxy A8 (Android 8 / minSdk 26, targetSdk 34, 3 GB RAM), sıfır harici kütüphane.
- Codespaces (Ubuntu): Geliştirici telefondan çalışır. Komutlar tek satırda ve tek kod bloğu içinde verilmeli (iç içe üçlü backtick kullanılmamalı), çıktı `rm -f kontrol.txt` sonrası `kontrol.txt` dosyasına yazılmalı; mobil yapıştırma için komutlar kısa tutulmalıdır.
- Tasarım: Mevcut sıcak tema (`14.5sp`) ve Web sekmesindeki Arka plan görüntü kapalı modu RAM tasarrufu için korunmalıdır.

## v3.0 Yenilikler
- **Raphael Code (2. Aşama):** `/sdcard/raphael/` içinde `CodeWorkspace`, `CodeProtocol` (`rph` komutları) ve `CodeUi` (`Raphael | Code` sekmeleri, `/code`, `/oku`, `/files`, `/undo`, `/chat`, `RAPHAEL.md`, Güvenli/Özgür mod, onay kartları, `.raphael_undo/`) ile AI Studio dosya/kod ajanı.
- **Arka Plan Servisi:** `AppCore` (`UiBridge`), `RaphaelService` (kalıcı bildirim + `Durdur`, `WakeLock`) ve `WebSession.detach()` ile arka planda kesintisiz çalışma ve dönüşte arayüz restorasyonu.
- **Bağlam Aktarımı & Web'den Sohbet Al:** Kaynak/model değişiminde geçmiş aktarımı ve Gemini sohbetlerini görselleriyle içe aktarma (`listChats`, `readChat`).
- **Sürücüler:** `gemini.js` çok satırlı `putText()`, `aistudio.js` `setSystemInstruction`, `common.js` arka plan `vis()` ve `rawTextLen()`.

## v2.0 Özellikler
- Gemini görseller: `inSkip()` ile `body.enable-mweb-header-mavatar` engeli aşıldı, `blob:` `<canvas>` yedeği ve Android 8 uyumlu AVIF-harici indirme eklendi.
- AI Studio düşünme: Süreç cevaba karışmaz; açılır/kapanır `▸ Düşünüyor…` / `▸ Düşünce` kutucuğunda tutulur.
- AI Studio token & Output: Üst çubukta toplam sohbet tokenı, gönderilen dosya yanında tokenı ve alt menüde `65K / 262K / 1M / Özel` Output ayarı (`maxOutputTokens`, varsayılan `65536`).
- Kod blokları & hız: Tamamlanan blok önbelleği + adaptif yoklama; `260dp` kaydırmalı kod penceresi; doğrudan `Download/` kaydı için `İndir` (otomatik dil uzantısı) ve `Kopyala`.
- HTML Preview koruması: AI Studio bitişte HTML bloğunu Preview moduna çevirse bile `WebSession` kodu kaybetmez, `aistudio.js` kod görünümüne çevirir ve `langOf()` `.title-text` okur.
- Tam ekran görselde ayrı `Kaydet` (doğrudan `Download/`, Android 8 izin uyumlu) ve `Paylaş`; dosya ön-yükleme (`invalidateUploads`, `uploadGen`, `topAtts()`).

## Kurulum ve Derleme (Codespaces)
- İlk kurulum ve derleme (JDK 17, Android SDK 34, Gradle 8.4): `bash build.sh` (çıktı: `Raphael.apk`). Sonraki: `./gradlew --no-daemon assembleDebug`.
- Temiz kaynak zip açıp sıfırdan derleme (`raphael-v3-kaynak.zip`): `cd "$(git rev-parse --show-toplevel)" && find . -mindepth 1 -maxdepth 1 ! -name .git ! -name raphael-v3-kaynak.zip -exec rm -rf {} + && unzip -oq raphael-v3-kaynak.zip && rm -f raphael-v3-kaynak.zip && bash build.sh`
- AI sohbetine `.js/.xml/.sh` dahil tek `.txt` almak için: `{ for f in build.sh build.gradle settings.gradle gradle.properties app/build.gradle app/src/main/AndroidManifest.xml app/src/main/res/values/styles.xml app/src/main/assets/js/*.js app/src/main/java/com/raphael/app/*.java app/src/main/java/com/raphael/app/*/*.java; do echo "=== $f ==="; cat "$f"; done; } > tum_kod.txt`

## Dosya Haritası
- `MainActivity.java`: `AppCore.UiBridge` arayüzü, sohbet/web/Code ekranları, arka plan akış restorasyonu, web'den sohbet alma, doğrudan `Download/` kayıt.
- `core/AppCore.java` & `RaphaelService.java`: Tekil oturum/durum yöneticisi, `WakeLock` ve kalıcı foreground bildirim servisi.
- `code/CodeProtocol.java`, `CodeWorkspace.java`, `CodeUi.java`: `rph` ayrıştırıcı, `/sdcard/raphael/` sandbox dosya/yama/geri alma motoru ve Code bileşenleri.
- `FileProv.java`: `imgs/`, `export/`, `up/` ContentProvider (`com.raphael.app.files`).
- `core/Site.java` & `WebSession.java`: Tekil WebView, `attach`/`detach`, `setSystemInstruction`, `fetchImages`, adaptif takip.
- `ui/MdView.java` & `Ui.java`: Dahili Markdown çizici (`260dp` kod kutusu + `İndir`/`Kopyala`), sıcak tema.
- `data/Chat.java`, `Msg.java`, `ChatStore.java`: Sohbet JSON geçmişi (`codeDir`/`codeAuto` dahil) ve görseller.
- `assets/js/common.js`, `gemini.js`, `aistudio.js`: DOM->Markdown, `putText`, `listChats`/`readChat`, `setSystemInstruction`, `vis()`.
- `AndroidManifest.xml`, `res/`, `build.sh`: Foreground/depolama izinleri, `RaphaelService`, ikonlar ve derleme betiği.

## Yol Haritası
- 1. Aşama devamı: `ChatGPT` ve `DeepSeek` sürücüleri.
- 2. Aşama: Tamamlandı (Raphael Code yerel çalışma alanı ve geri alma).
- 3. Aşama: Çoklu model ajan orkestrasyonu.
