# ☁️ StreamTest - CloudStream Anime & Dizi Eklentileri

> **Geliştirici:** Jung1330  
> **Not:** Bu eklenti deposu ve modülleri **AI (Yapay Zeka)** desteği ile analiz edilmiş, tersine mühendislik ile API korumaları çözülerek güncellenmiş ve optimize edilmiştir.

Bu depo, **CloudStream 3** uygulaması için en güncel Türkçe anime ve dizi sağlayıcılarını içerir.

---

## 🌟 İçerik ve Özellikler

* **AnimeciX (`animecix.tv`):**
  * Dinamik **X-E-H (AES-GCM)** yetkilendirme şifrelemesi eklendi.
  * **TauVideo (`tau-video.xyz`)** 1080p, 720p, 480p doğrudan video ayıklayıcısı entegre edildi.
  * CloudStream'in yerel **Tüm Sezonu İndir (Download Season)** özelliğiyle tam uyumlu.
* **Diziwatch (`diziwatch.org`):**
  * Hızlı ve temiz REST arama API'si entegrasyonu.
  * Voe, Vidmoly, Sibnet, Mail.ru, OK.ru çoklu oynatıcı desteği ile 1080p Full HD yayın.
  * Sezon ve bölüm listeleme.
* **TRAnimeİzle (`tranimeizle.live`):**
  * Güncel ve popüler seriler için alternatif Türkçe anime akışı.
* **TurkAnime:**
  * Eski Türkçe anime arşivi modülü (Score API güncellemesi yapılmıştır).

---

## 💾 Kurulum (CloudStream)

1. **[CloudStream APK](https://github.com/recloudstream/cloudstream/releases)** son sürümünü cihazınıza kurun.
2. Uygulama içinde:
   * **Ayarlar (Settings) > Uzantılar (Extensions) > Depo Ekle (Add Repository)** yolunu izleyin.
   * Depo URL kısmına şu bağlantıyı yapıştırın:
   ```text
   https://raw.githubusercontent.com/Jung1330/StreamTest/main/repo.json
   ```
3. Eklentiler listesinden dilediğiniz eklentiyi kurup izleme ve indirme özelliklerini kullanabilirsiniz.

---

## 🛠️ Teknik Bilgi

* **Mimar:** Jung1330 & AI-Assistant
* **Dil:** Kotlin (Android / CloudStream 3 API)
* **Derleme:** GitHub Actions (`./gradlew make makePluginsJson`)
