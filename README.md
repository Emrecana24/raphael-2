# Raphael (v1.0)

Android (native Java) için hafif yapay zeka sohbet kabuğu. Gemini (gemini.google.com) ve AI Studio (aistudio.google.com) web arayüzlerini tek oturumlu arka plan WebView içinde çalıştırır.

## Temel Özellikler (v1.0)
- Sıfır harici bağımlılık, ~106 KB APK (Samsung Galaxy A8 / Android 8 / 3 GB RAM hedefli).
- Dahili Markdown çizici (MdView): başlıklar, listeler, tablolar, ayrı kod kutuları ve her kod bloğunda Kopyala düğmesi.
- Çoklu kaynak (Gemini ve AI Studio): tek WebView çerezleriyle geçiş, farklı modele veya eski sohbete geçince bağlam aktarımı.
- Düşünme düzeyi seçimi (Varsayılan, Low, Medium, High; AI Studio için ek Extra High) ve AI Studio model seçici.
- Dosya ekleme (+): metin/kod dosyaları mesaja gömülür; medya ve ikili dosyalar seçilir seçilmez siteye ön-yüklenir, AI Studio token bilgisi gösterilir, çipten silinince web sayfasından da kaldırılır.
- Üretilen görselleri algılama (Shadow DOM ve lazy-load destekli), cookie/redirect uyumlu indirme, tam ekran görüntüleme ve paylaşma.
- Yerel sohbet geçmişi (JSON), sohbet dışa aktarma (.md), Web sekmesinden canlı kontrol, arka plan görüntü aç/kapa, bağlantı kesme ve teşhis için DOM kaydet.

## Kurulum ve APK Derleme (GitHub Codespaces)
- İlk kurulum ve derleme (JDK 17, Android SDK 34, Gradle 8.4 otomatik kurulur): `bash build.sh` (çıktı: `Raphael.apk`).
- Sonraki hızlı derlemeler: `./gradlew --no-daemon assembleDebug`.

## Proje Haritası (AI Asistanlar İçin Özet)
- `app/src/main/java/com/raphael/app/MainActivity.java`: Ana arayüz, sohbet/web sekmesi, çekmece, model/düşünme menüsü, anlık dosya ön-yükleme (`pumpPreUpload`), akış sayacı, ayarlar.
- `app/src/main/java/com/raphael/app/FileProv.java`: Paylaşım (`imgs/`, `export/`) ve WebView dosya yükleme (`up/`) için dahili ContentProvider (`com.raphael.app.files`).
- `app/src/main/java/com/raphael/app/core/Site.java`: Desteklenen siteler (`GEMINI`, `AISTUDIO`). Yeni site = yeni enum + `assets/js/<site>.js`.
- `app/src/main/java/com/raphael/app/core/WebSession.java`: WebView yönetimi, UA seçimi (Chrome/Firefox), `AndroidBridge` köprüsü, `window.__rph` komutları, yanıt izleme, görsel indirme, DOM kaydı.
- `app/src/main/java/com/raphael/app/ui/MdView.java` & `Ui.java`: Kütüphanesiz Markdown çizici ve tema yardımcıları.
- `app/src/main/java/com/raphael/app/data/Chat.java`, `Msg.java`, `ChatStore.java`: Sohbet geçmişi (`files/chats/`) ve görseller (`files/imgs/`).
- `app/src/main/assets/js/common.js`: Ortak DOM/Markdown motoru, Shadow DOM uyumlu düşünme filtresi (`isThNode`/`inTh`), görsel tarayıcı (`imgsIn`), parça parça dosya ekleme (`ops.attach`), dosya kaldırma (`ops.removeAttach`), DOM dışa aktarma (`ops.dom`).
- `app/src/main/assets/js/gemini.js` & `aistudio.js`: Sitelere özel `CFG` seçicileri, gönderim (`ops.send`), düşünme düzeyi (`ops.setThinking`), model seçici (`ops.listModels`, `ops.setModel`), üretim takibi (`isGen`, `state`).
- `app/src/main/AndroidManifest.xml`, `app/src/main/res/`, `build.sh`: İzinler (`INTERNET`), `configChanges` koruması, vektör ikonlar ve derleme betiği.

## AI Sohbetine Eksiksiz Kod Verme Notu
Bazı AI sohbet arayüzleri `.zip` içindeki `.js`, `.xml` ve `.sh` dosyalarını filtreleyebilir. Tüm kaynak kodunu tek `.txt` halinde almak için Codespaces içinde şu komutu kullanabilirsiniz:
`{ for f in build.sh build.gradle settings.gradle gradle.properties app/build.gradle app/src/main/AndroidManifest.xml app/src/main/res/values/styles.xml app/src/main/assets/js/*.js app/src/main/java/com/raphael/app/*.java app/src/main/java/com/raphael/app/*/*.java; do echo "=== $f ==="; cat "$f"; done; } > tum_kod.txt`

## v1 Bilinen Sınırlamalar (v2 İlk Düzeltme Listesi)
Bu sürüm kararlı v1 referansı olarak dondurulmuştur; aşağıdaki bilinen kenar durumlar v2 geliştirme sürecinde ele alınacaktır:
1. `MainActivity`: Bağlantıyı kes -> Bağlan veya Yenile sonrası, ya da başka sohbete geçip 2. dosya eklendiğinde ön-yüklenmiş (`upState=2`) dosyaların durumu sıfırlanmadığı için gönderimde atlanabilmesi.
2. `MainActivity`: AI cevap yazarken (`generating=true`) eklenen dosyalar için `finishAi`/`failAi` bitişinde `pumpPreUpload` çağrılmadığından dosyanın `loading…` durumunda kalabilmesi.
3. `WebSession`: `open()` içinde eski `queue` kuyruğu temizlenmediği için sayfa açılırken site değiştirildiğinde çift yükleme yarışması oluşabilmesi.
4. `MainActivity`: Yüklenmekte olan dosya `x` ile silinip hemen Gönder basıldığında `activeUpload` çakışması; `resendLast()` içinde ön-yüklenmiş dosyaların tekrar yüklenmemesi.
5. `common.js`: Gemini görsel önizlemelerinde `ATT_SEL` kapsayıcı + `img[src^="blob:"]` çift eşleşmesi yüzünden `ops.removeAttach` işleminin başarısız olabilmesi.

## Uzun Vadeli Plan
- 1. Aşama devamı: v2 hata düzeltmeleri, ChatGPT ve DeepSeek sürücüleri, genel token sayacı ve isteğe bağlı daha kompakt arayüz.
- 2. Aşama: Yerel `raphael` klasörü üzerinde dosya araçları.
- 3. Aşama: Çoklu model ajan orkestrasyonu.
