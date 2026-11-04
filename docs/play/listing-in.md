# Daftar Play Store (draf)

Hanya teks draf. Tidak ada yang dikirim. Sumber aturannya: halaman daftar toko
https://support.google.com/googleplay/android-developer/answer/9866151 (disebut di
docs/roadmap.md, "Before public release (v1.0)", Store listing).

Fakta di bawah berasal dari kode dan halaman privasi. Yang lain ditandai BELUM DIKONFIRMASI.

Nama aplikasi: **LocalIntelligence** (di semua bahasa).

---

## Bahasa Indonesia

**Deskripsi singkat** (maksimal 80 karakter; 75 karakter)

> Asisten AI pribadi, modelnya berjalan di ponselmu. Tanpa akun, tanpa iklan.

**Deskripsi lengkap**

LocalIntelligence adalah asisten AI untuk Android yang menjalankan model bahasanya di ponselmu.

Pilih model yang sudah kamu punya, atau unduh model yang disarankan. Di mode Sederhana, aplikasi
hanya menawarkan model yang disarankan. Mode Teknis menambahkan sisa katalog Hugging Face dan
kunci milikmu sendiri untuk layanan jarak jauh.

Yang bisa kamu minta, saat kamu memintanya:
- Menjawab pertanyaan dan menyelesaikan tugas bertahap dalam obrolan.
- Memasang alarm dan timer, serta melihat atau menambahkan acara kalender.
- Mencari kontak, membuka aplikasi, membaca file yang kamu pilih, atau mengecek baterai.
- Membaca notifikasi yang kamu izinkan untuk dilihat, dan membalasnya.
- Membaca pesan teks dan riwayat panggilan terbaru, serta mengirim pesan teks, hanya jika kamu
  menjadikan LocalIntelligence sebagai aplikasi asisten digital default. Mengirim pesan teks
  selalu bertanya dulu kepadamu.
- Mendikte ke obrolan, mengirim foto, atau merekam klip suara, jika modelnya mendukungnya.
- Menyalakan kata pemicu di Pengaturan, agar asisten terbuka tanpa perlu tangan.

Hal-hal yang meninggalkan ponselmu tetap mati sampai kamu mengaturnya, dan setiap langkah jaringan
bertanya dulu: pencarian dan pembacaan halaman web, cuaca, pencarian Wikipedia dan tempat, serta
penyedia model jarak jauh yang kuncinya kamu isi sendiri. Kebijakan privasi mencantumkan masing-masing
layanan dan data yang diterimanya.

Privasi: tanpa akun, tanpa analitik, tanpa pelaporan crash, tanpa iklan. Obrolan dan yang diingat
asisten tetap ada di ponsel, dan cadangan Android dimatikan untuk aplikasi ini. Balasan bisa
dilaporkan ke pengembang langsung dari balasan itu; kamu memilih alasannya, bisa menambahkan catatan,
dan bisa tidak menyertakan teks balasannya.

Kebijakan privasi: https://riceharvest.github.io/LocalIntelligence/privacy.html

**Kategori:** Produktivitas (usulan dari draf ini; BELUM DIKONFIRMASI, pemilik yang memilih di Play Console)

**Detail kontak**
- Situs web: tidak ada
- Cara menghubungi: isu GitHub, https://github.com/riceharvest/LocalIntelligence/issues
- Email: TERBUKA. Play Console meminta alamat email untuk daftar ini. Draf ini tidak mengisinya.
  Pakai alamat proyek yang dipilih pemilik, jangan pernah alamat pribadi.

---

## Checks

Deskripsi singkat dihitung dengan Python: 76 karakter (Inggris), 67 karakter (Belanda), dan
75 karakter (Indonesia).

Klaim yang sengaja tidak dimasukkan: kecepatan, pemakaian memori, kualitas jawaban, "bisa offline",
dan perbandingan dengan aplikasi lain. Belum ada yang diukur di perangkat (docs/roadmap.md,
docs/measurements.md).
