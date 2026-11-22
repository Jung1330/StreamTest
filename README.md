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
  * Voe, Sibnet, Mail.ru, OK.ru, AnizmPlayer çoklu oynatıcı desteği ile 1080p Full HD yayın.
  * Sayfalanan "Anime Arşivi" ve "Son Bölümler" kategorileri, sezon/bölüm listeleme.
* **TRAnimeİzle (`tranimeizle.live`):**
  * Site 2026-11'de Nuxt tabanlı yeni bir uygulamaya taşındı; eklenti artık sitenin JSON API'sini kullanıyor.
  * "Son Eklenenler", "En Çok Beğenilenler" ve "Tamamlananlar" sıralamaları (limit/offset sayfalama).
  * Katalog araması (`/api/search/catalog`), bölüm listesi ve **wid çerezli HLS** kaynak çözümü.
* **HDFilmCehennemi (`hdfilmcehennemi.nl`):**
  * Film ve yabancı dizi kategorileri, yerel JSON + DuckDuckGo destekli arama.
  * Kendi oynatıcısındaki (`rplayer` / `CloseLoad`) şifreli yayın adresini çözer; çift sesli (TR Dublaj / Orijinal) birleşik (muxed) HLS akışları ile **izleme ve indirme** tam uyumlu.
* **FilmMakinesi (`filmmakinesi.to`):**
  * Sayfalanan film ve dizi listesi, site içi arama.
  * Şifreli oynatıcı çözümü, çift sesli birleşik (muxed) HLS kaliteleri, altyazılar ve tam indirme desteği.
* **DiziBOX (`dizibox.live`):**
  * Yabancı dizi arşivi, IMDb sıralaması ve tür kategorileri (sayfalama destekli).
  * **DBX Pro (King/Molystream AES)**, **Moly+ (VidMoly)** ve alternatif oynatıcı çözümü; 1080p HLS, Türkçe altyazı ve tam indirme desteği.
* **JetFilmizle (`jetfilmizle.app`):**
  * Türkçe Dublaj & Altyazılı filmler, yabancı ve yerli diziler (sayfalama destekli).
  * **OPlay**, **Gold**, **Vip**, **JetGlobal** ve çoklu oynatıcılarla doğrudan MP4 / 1080p HLS akışı ve sorunsuz indirme desteği.
* **KoreanTürk (`koreanturk.tv`):**
  * Kore dizileri, Kore filmleri ve BL dizileri arşivi (sayfalama destekli).
  * HintPlayer, VidMoly, OK.ru, Sibnet, VK çoklu oynatıcı ve indirme desteği.
* **Dizilla (`dizilla.la`) & Diziyou (`diziyou.love`):**
  * Yabancı dizi arşivleri, sezon/bölüm listeleme, 1080p HLS yayın ve indirme uyumluluğu.
  * Dizilla'da **Pichive** oynatıcısı için **NP_GATE (SHA-256 proof-of-work)** çözümü eklendi.
* **HDKore (`hdkore1.com`) & DramaDizilerim (`dramadizilerim1.com`):**
  * Asya & Kore dizileri, şifreli oynatıcı çözümü ve indirme uyumluluğu.
* **Dizimag (`ddizimag2.icu`):**
  * Yabancı dizi, film, anime ve platform (Netflix, Disney+, Amazon Prime, Apple TV+) katalogları.
  * **Pilavyer** HLS oynatıcı ve Türkçe altyazı desteği.
* **Filmmodu (`filmmodu.cc`):**
  * Yeni, trend ve popüler filmler, yabancı diziler ve tür arşivleri.
  * **Bepeak (CryptoJS AES)** şifreli HLS oynatıcı ve altyazı çözümü.
* **WebDramaTurkey (`webdramaturkey2.com`):**
  * Kore, Çin, Japon ve Tayland dizileri, Asya filmleri ve animeler.
  * FirePlayer ve çoklu alternatif oynatıcı desteği.
* **AsyaAnimeleri (`asyaanimeleri.top`):**
  * Güncel anime, donghua ve anime filmleri arşivi; FirePlayer (`asyaanimeleri.pw`) ve çoklu alternatif oynatıcı desteği.
* **MiniDiziİzle (`minidiziizle.com`):**
  * Türkçe Dublaj ve Altyazılı dikey/kısa mini diziler, doğrudan **1080p HLS (`master.m3u8`)** oynatıcı ve arama API desteği.
* **Shortizle (`shortizle.com`):**
  * Türkçe Dublaj & Altyazılı kısa/mini diziler, K-Drama ve filmler; doğrudan **1080p HLS** kaynak çözümü.
* **DramaDünyam (`dramadunyam.com`):**
  * Türkçe Dublaj & Altyazılı popüler kısa dramalar (NetShort, DramaWave, ReelShort vb.) doğrudan MP4 / HLS oynatma desteği.
* **DiziCaN (`dizican.cc`) & AsyaDizi (`asyadizi.com`):**
  * Türkçe altyazılı Kore, Çin, Japon, Tayland ve BL dizileri ile Asya filmleri arşivi (VidMoly, OK.ru, HintPlayer destekli).
* **MyAsianTV (`ww7.myasiantv.rest` — EN):**
  * **İngilizce Altyazılı (English Sub)** Kore, Çin, Japon ve Tayland dizileri ile Asya filmleri (VidMoly, Streamtape, MixDrop destekli).

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
