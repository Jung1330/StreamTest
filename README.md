# ☁️ StreamTest - CloudStream Anime Eklentileri

> **Not:** Bu eklenti deposu ve modülleri **AI (Yapay Zeka)** desteği ile analiz edilmiş, tersine mühendislik ile API korumaları çözülerek güncellenmiş ve optimize edilmiştir.

Bu depo, **CloudStream 3** uygulaması için güncel Türkçe anime sağlayıcılarını (**AnimeciX** ve **TurkAnime**) içerir.

---

## 🌟 İçerik ve Özellikler

* **AnimeciX:**
  * Güncel domain: `https://animecix.tv`
  * Dinamik **X-E-H (AES-GCM)** yetkilendirme şifrelemesi eklendi.
  * **TauVideo (`tau-video.xyz`)** video oynatıcı ve doğrudan MP4/HLS akış ayıklayıcısı entegre edildi.
  * CloudStream'in **Tüm Sezonu İndir (Download Season)** ve kalite seçimi (1080p, 720p, 480p) özellikleriyle tam uyumlu.
* **TurkAnime:**
  * Türkçe anime arşivi desteği.

---

## 💾 Kurulum (CloudStream)

1. **[CloudStream APK](https://github.com/recloudstream/cloudstream/releases)** son sürümünü cihazınıza kurun.
2. Uygulama içinde:
   * **Ayarlar (Settings) > Uzantılar (Extensions) > Depo Ekle (Add Repository)** yolunu izleyin.
   * Depo URL kısmına bu deponun `repo.json` ham (raw) bağlantısını yapıştırın.
3. Eklentiler listesinden **AnimeciX** eklentisini yükleyip arama ve izleme/indirme yapabilirsiniz.

---

## 🛠️ Teknik Bilgi & Geliştirme

* **Mimar:** StreamTest (AI-Assisted)
* **Dil:** Kotlin (Android / CloudStream 3 API)
* **Derleme:** Gradle (`./gradlew make makePluginsJson`)
