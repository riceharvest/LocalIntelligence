# Play mağaza kaydı (taslak, Türkçe)

Yalnızca taslak metin. Hiçbir şey gönderilmez. İngilizce kaynak: docs/play/listing.md.

Uygulama adı: **LocalIntelligence** (değişmez).

---

**Kısa açıklama** (en fazla 80 karakter)

> Modellerini telefonunda çalıştıran özel AI asistanı. Hesap yok, reklam yok.

**Tam açıklama**

LocalIntelligence, dil modelini telefonunda çalıştıran bir Android yapay zeka asistanıdır.

Zaten sahip olduğun bir modeli seç ya da önerilen bir modeli indir. Basit modda uygulama yalnızca önerilen modelleri sunar. Teknik modda Hugging Face kataloğunun geri kalanını ve uzak hizmetler için kendi anahtarlarını kullanabilirsin.

İstediğinde asistandan yapabileceklerin:
- Bir sohbette soruları yanıtlamak ve çok adımlı işleri adım adım yürütmek.
- Alarm ve zamanlayıcı kurmak, takvim etkinliklerine bakmak ya da yeni etkinlik eklemek.
- Kişilere bakmak, uygulama açmak, seçtiğin bir dosyayı okumak ya da pili kontrol etmek.
- Görmesine izin verdiğin bildirimleri okumak ve bunlara yanıt vermek.
- Mesajlarını ve son aramalarını okumak, ve mesaj göndermek; yalnızca LocalIntelligence'ı varsayılan dijital asistan uygulaman yaparsan. Mesaj göndermek her zaman önce sana sorar.
- Sohbete sesle yazdırmak, bir fotoğraf göndermek ya da bir ses kaydı yapmak; model bunları kabul ediyorsa.
- Ayarlardan bir uyandırma sözcüğü açmak; böylece asistan eller serbest açılır.

Telefonundan çıkan şeyler, sen kurana kadar kapalıdır ve her ağ adımından önce sana sorulur: web araması ve sayfa okuma, hava durumu, Vikipedi ve yer araması, bir de kendi anahtarını girdiğin uzak model sağlayıcıları. Gizlilik politikası, bunların her birinin neyi aldığını listeler.

Gizlilik: hesap yok, analiz yok, çökme raporlaması yok, reklam yok. Sohbetlerin ve asistanın hatırladıkları telefonda kalır; uygulama için Android yedeklemesi kapalıdır. Yanıtları, yanıtın kendisinden geliştiriciye bildirebilirsin; nedeni sen seçersin, bir not ekleyebilirsin ve yanıt metnini dahil etmeyebilirsin.

Gizlilik politikası: https://riceharvest.github.io/LocalIntelligence/privacy.html

**Kategori:** Üretkenlik (bu taslağın önerisi; ONAYLANMADI, sahibi Play Console'da seçer)

**İletişim bilgileri**
- Web sitesi: yok
- İletişim yöntemi: GitHub sorunları, https://github.com/riceharvest/LocalIntelligence/issues
- E-posta: AÇIK. Play Console, kayıt için bir e-posta adresi ister. Bu taslak bir adres yazmaz. Sahibin seçtiği bir proje adresini kullan, asla kişisel bir adresi değil.

---

## Kontroller

Kısa açıklama Python ile sayıldı: 75 karakter.

Bilerek dışarıda bırakılan iddialar: hız, bellek kullanımı, yanıt kalitesi, "çevrimdışı çalışır" ve diğer uygulamalarla karşılaştırma. Bunların hiçbiri bir cihazda ölçülmedi (docs/roadmap.md, docs/measurements.md).
