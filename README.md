<p align="center">
  <img src="docs/assets/lin-vpn.gif" alt="Lin Vpn" width="720">
</p>

# Lin Vpn

Aplikasi VPN tunneling untuk Android yang ringan, rapi, dan nyaman dipakai di ponsel maupun tablet. Cukup isi akun, pilih metode, lalu sambungkan.

## Fitur

- **Banyak metode koneksi:** SSH WebSocket / Payload, SSH TLS, SSH SlowDNS, V2Ray, dan V2Ray SlowDNS.
- **Profil:** simpan banyak akun sekaligus, ganti dengan satu ketukan, duplikat, ekspor, dan impor.
- **SlowDNS lengkap:** pilihan tipe koneksi, transport DNS, name server, dan public key.
- **Beranda informatif:** tombol sambung, durasi, kecepatan unduh/unggah secara langsung, IP lokal, dan pesan dari penyedia akun.
- **Notifikasi praktis:** tombol Putuskan dan Hubungkan ulang, ditambah kecepatan yang terus diperbarui.
- **Sambung ulang otomatis** saat koneksi terputus atau jaringan berganti.
- **Log langsung** dengan gulir otomatis, bisa disalin dan dibagikan.
- **Pengaturan lengkap:** tema, DNS, MTU, IPv6, UDP, VPN per aplikasi, mulai otomatis, dan banyak lagi.
- Tampilan menyesuaikan semua ukuran layar.

## Persyaratan

Android 6.0 atau lebih baru, 32-bit maupun 64-bit.

## Memasang

1. Buka halaman **Releases** di repositori ini.
2. Unduh APK yang sesuai:
   - `arm64-v8a` untuk hampir semua ponsel terbaru,
   - `armeabi-v7a` untuk perangkat 32-bit,
   - `universal` bila ragu (ukurannya lebih besar).
3. Buka berkas APK dan izinkan pemasangan dari sumber ini bila diminta.

## Membangun sendiri dengan GitHub Actions

Tidak perlu memasang apa pun di komputer.

1. Unggah proyek ini ke repositori GitHub milik Anda.
2. Buka **Settings → Actions → General → Workflow permissions**, pilih **Read and write permissions**, lalu simpan.
3. Buka tab **Actions → Build & Release**. Build berjalan otomatis setiap ada perubahan di cabang `main`.
4. Setelah selesai, APK ada di bagian **Artifacts** pada halaman proses build tersebut.

Build pertama memakan waktu lebih lama karena komponen pendukung dibangun dari awal; build berikutnya memakai cache.

## Membuat rilis

Pilih salah satu cara:

- **Lewat tag:** buat tag berawalan `v`, misalnya `v1.0.0`, lalu dorong ke GitHub.
  ```bash
  git tag v1.0.0
  git push origin v1.0.0
  ```
- **Lewat tombol:** buka **Actions → Build & Release → Run workflow**, isi kolom *versi* (misalnya `1.0.0`), lalu jalankan.

APK akan terunggah otomatis ke halaman **Releases**, siap dibagikan.

### Tentang keystore

Anda tidak perlu membuat keystore sendiri. Pada build pertama, workflow membuatnya otomatis dan menyimpannya di cache repositori supaya tanda tangan APK tetap sama pada rilis berikutnya, sehingga pembaruan bisa dipasang di atas versi lama.

Agar tanda tangan benar-benar permanen (cache bisa terhapus bila lama tidak dipakai), simpan keystore Anda di **Settings → Secrets and variables → Actions** dengan nama `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, dan `KEY_PASSWORD`. Bila sudah ada, workflow memakainya otomatis. Pada repositori privat, cadangan keystore otomatis juga tersedia di Artifacts build pertama.

## Catatan

Gunakan dengan bijak dan patuhi hukum serta ketentuan penyedia layanan yang Anda pakai.
