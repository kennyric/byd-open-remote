# Overdrive akses jarak jauh: Tailscale + pengawasan saat parkir

> English: [Overdrive remote access guide](overdrive-remote-access.md)

Panduan ini membuat server Overdrive di mobil bisa dijangkau dari ponsel Anda **dari mana saja** —
termasuk saat mobil diparkir dan layarnya mati — lalu menghubungkan BYD Open Remote ke sana.

Isinya tiga hal:

1. Overdrive di head unit, beserta akses latar belakang/ADB yang dibutuhkannya.
2. Pengawasan saat parkir + Sentry Daemon, agar head unit tetap punya data seluler saat mobil mati.
3. Tunnel Tailscale, agar ponsel bisa mencapai mobil tanpa membuka port router.

> Nama menu di dalam Overdrive berbeda antarversi alpha dan antar-DiLink 3/4/5. Jika labelnya tidak
> persis sama, cari yang paling mendekati; alurnya tetap sama.

---

## Ringkas (TL;DR)

1. Pasang Overdrive di head unit dan izinkan permintaan ADB-nya.
2. Overdrive → **Sentry Daemon** + **pengawasan saat parkir** → jaga mobil tetap online saat diparkir.
3. Overdrive → **Tailscale** → buat URL login → masuk di ponsel → catat alamat mobil.
4. BYD Open Remote → **Lanjutan** → alamat `http://<ip-tailscale-mobil>:8080` → simpan.
5. Status menampilkan **Perlu login** → masukkan kode akses Overdrive → **Hubungkan**.
6. Menu samping → **Overdrive** (atau kartu kontrol di halaman utama, jika diaktifkan).

---

## Kebutuhan

| Item | Catatan |
|---|---|
| BYD dengan DiLink 3, 4, atau 5 | Overdrive mendukung head unit arm64, Android 10+ |
| Sistem kamera 360° | Dibutuhkan untuk fitur pengawasan/kamera Overdrive |
| ADB nirkabel di head unit | Overdrive memintanya saat pertama dijalankan |
| Akun Tailscale | Gratis untuk pemakaian pribadi |
| BYD Open Remote ≥ 1.3.2 | Build lama tidak bisa membuka halaman `http://` di versi rilis |
| Paket data aktif di mobil | Kartu SIM BYD (atau hotspot) harus tetap online saat diparkir |

---

## 1. Overdrive di mobil

1. Unduh APK Overdrive terbaru dari halaman Releases-nya dan pasang di head unit
   (panduan pemasangan proyek ini menunjukkan langkah ADB/Termux untuk versi DiLink Anda).
2. Aktifkan **ADB nirkabel** di head unit.
3. Buka Overdrive dan setujui permintaan otorisasi ADB.
4. Biarkan **Nonaktifkan Autostart** dalam keadaan mati, agar aplikasi bisa kembali setelah mobil tidur.
5. Buka UI web Overdrive sekali di layar mobil dan selesaikan pengaturan awal.

Overdrive menjalankan server web di port **8080**:

```
http://<ip-mobil>:8080
```

**Hubungkan ponsel ke jaringan Wi-Fi yang sama dengan mobil untuk langkah pertama ini.** Buka URL
di atas di ponsel untuk memastikan head unit bisa dijangkau sebelum mengatur hal lain.

### Di mana menemukan IP, port, dan kode akses

Di head unit:

- **Alamat IP** — Overdrive menampilkan alamat servernya di dasbor atau di kartu pengaturan/status,
  misalnya `http://192.168.1.50:8080`. Jika yang tampil hanya hostname, lihat IP Wi-Fi head unit di
  pengaturan Wi-Fi Android.
- **Port** — **8080** secara bawaan. Port ini bagian dari alamat yang ditampilkan Overdrive; ubah
  hanya jika Anda memang mengganti port servernya.
- **Kode akses** — tampil di halaman utama/dasbor Overdrive di head unit, biasanya sebagai
  “Access code: …”. Anda juga bisa melihat atau menggantinya di **Pengaturan → Akses / Keamanan**
  (label persisnya berbeda antar-build). Salin kode ini — inilah yang nanti Anda masukkan di
  BYD Open Remote. Perlakukan seperti kunci mobil.

### Sebelum mulai: matikan Traffic Monitor head unit

**Traffic Monitor / batas data** DiLink bisa menghentikan atau membatasi data latar belakang
Overdrive, terutama saat mobil diparkir. Nonaktifkan (atau naikkan batasnya jauh di atas pemakaian
Overdrive) saat pengaturan, dan sebaiknya biarkan mati untuk pemakaian saat parkir.

Di Wi-Fi yang sama ini sudah berfungsi; sisa panduan ini membuatnya berfungsi dari mana saja.

---

## 2. Jaga mobil tetap online saat parkir (pengawasan / Sentry Daemon)

Ini langkah yang sering terlewat. Tanpa ini head unit akan tidur, tunnel Tailscale terputus, dan
mobil terlihat "offline" dari ponsel padahal hal lain sudah dikonfigurasi.

Di Overdrive:

1. Buka **Sentry Daemon** (pembantu "tetap bangun") dan aktifkan.
2. Aktifkan **pengawasan saat parkir** / mode Sentry.
3. Gunakan mode **Kendaraan ON + OFF** jika build Anda menyediakannya — mode inilah yang
   dimaksudkan untuk pemakaian saat parkir.
4. Setujui semua permintaan izin daya/jaringan.
5. Kecualikan Overdrive dari optimasi baterai di head unit, dan izinkan autostart.

Apa efeknya: daemon meminta akses daya dan jaringan yang dibutuhkan Overdrive agar tetap berjalan
saat mobil mati. **Firmware Anda yang menentukan permintaan mana yang diterima**, jadi selalu uji
(langkah 5).

Catatan praktis:

- Wajar ada pengurasan baterai 12 V; pengawasan saat parkir tidak gratis. Perjalanan singkat setiap
  hari akan mengisi ulang.
- Paket data SIM BYD harus aktif. Jika mobil kehilangan data seluler, tunnel tidak bisa menjangkau.
- Biarkan **Traffic Monitor / batas data** head unit mati (lihat Bagian 1), atau Overdrive bisa
  dibatasi saat parkir.
- Jika mobil tetap tidur, periksa lagi daemon, pengaturan autostart, dan opsi "jaga jaringan tetap
  hidup" milik firmware (namanya berbeda per model).

---

## 3. Tunnel Tailscale

Overdrive sudah punya Tailscale bawaan. **Jangan pasang aplikasi Tailscale kedua di head unit** —
Anda hanya perlu memasang Tailscale di ponsel.

### Di mobil

1. Overdrive → **Pengaturan → Tailscale**.
2. Buat URL login.
3. Buka URL itu di ponsel, masuk ke akun Tailscale Anda, dan setujui mobil.
4. Opsional, ganti nama perangkat mobil menjadi singkat dan netral (hanya label; tetap gunakan IP
   numerik di BYD Open Remote).
5. Catat **IP Tailscale numerik** mobil (diawali `100.`) di aplikasi Tailscale atau konsol admin.
   Gunakan IP itu di BYD Open Remote; jangan mengandalkan hostname.
6. Di konsol admin Tailscale, **nonaktifkan kedaluwarsa kunci** untuk mobil agar tidak terputus
   setiap enam bulan.

### Di ponsel

1. Pasang Tailscale dan masuk ke tailnet yang **sama**.
2. Biarkan Tailscale tetap terhubung (notifikasi VPN-nya harus terlihat).
3. Jika memakai ACL kustom, izinkan ponsel Anda mencapai mobil di port **8080**.

### Uji

Dengan ponsel memakai data seluler (Wi-Fi mati), buka:

```
http://<ip-tailscale-mobil>:8080
```

Anda seharusnya melihat halaman login Overdrive. Jika berhasil, bagian tersulit sudah lewat.

---

## 4. Hubungkan BYD Open Remote

Buka **BYD Open Remote → Lanjutan**.

| Kolom | Yang diisi |
|---|---|
| Alamat | `http://<ip-tailscale-mobil>:8080` — gunakan alamat numerik `100.x` dari Tailscale |
| Halaman | Preset seperti `Vehicle control`, atau **URL manual** untuk halaman tertentu |
| Kode akses Overdrive | Kode yang tampil di halaman utama Overdrive (lihat Bagian 1) |

Lalu:

1. Ketuk **Simpan**.
2. Kembali ke Lanjutan; statusnya harus berubah dari **Tidak dapat dijangkau**.
3. Jika tertulis **Perlu login**, masukkan kode akses dan ketuk **Hubungkan**.
4. Setelah tertulis **Terhubung**, buka **Overdrive** dari menu samping.

Yang perlu diketahui:

- Kolom alamat menerima URL lengkap hasil tempel (`http://…/login.html?redirect=/index.html`);
  hanya host dan port yang dipakai untuk login dan kontrol. URL halaman yang ditempel tetap dipakai
  di WebView.
- Mengganti alamat akan menghapus sesi Overdrive — memang begitu desainnya; masukkan lagi kode
  akses setelah mengganti.
- Kode akses adalah rahasia perangkat berjangka panjang. Kode ini disimpan terenkripsi di ponsel.
  Jika Anda menggantinya di Overdrive, hubungkan ulang di sini.
- **Lanjutan → Sesi BYD Cloud** adalah pengaturan berbeda: itu mengatur seberapa sering aplikasi
  mengambil token BYD Cloud baru. Tidak ada hubungannya dengan Overdrive.

Opsional: **Lanjutan → Kontrol Overdrive di halaman utama** menentukan apakah kartu batas arus AC /
jadwal pengisian selalu tampil, tampil hanya saat terhubung, atau disembunyikan.

---

## 5. Daftar uji

Lakukan sekali, saat parkir dan mobil terkunci:

- [ ] Ponsel hanya memakai **data seluler**, Tailscale terhubung.
- [ ] `http://<ip-tailscale-mobil>:8080` membuka halaman Overdrive.
- [ ] BYD Open Remote → Lanjutan menampilkan **Terhubung**.
- [ ] Menu samping → Overdrive membuka UI mobil dan login tetap tersimpan.
- [ ] Kunci mobil, tunggu 10–15 menit, lalu muat ulang halaman dari ponsel.
- [ ] Tidak ada error `ERR_CLEARTEXT_NOT_PERMITTED` (perlu BYD Open Remote ≥ 1.3.2).
- [ ] Opsional: kartu batas arus AC di halaman utama menampilkan nilai langsung saat parkir.

Jika dua pemeriksaan terakhir gagal setelah parkir, langkah Sentry Daemon / pengawasan belum
menjaga mobil tetap online.

---

## Pemecahan masalah

| Gejala | Kemungkinan penyebab | Perbaikan |
|---|---|---|
| **Tidak dapat dijangkau** | Tailscale di ponsel mati, atau mobil tidur/offline | Periksa aplikasi Tailscale; bangunkan mobil; uji URL di browser ponsel |
| Berfungsi di Wi-Fi, tidak di data seluler | Mobil kehilangan data seluler saat parkir | Aktifkan Sentry Daemon + pengawasan; periksa paket SIM BYD |
| **Perlu login** | Kode akses belum diisi, JWT kedaluwarsa, atau alamat berubah | Masukkan kode lagi; hubungkan ulang |
| Sempat berfungsi, lalu berhenti setelah berbulan-bulan | Kunci Tailscale kedaluwarsa | Nonaktifkan kedaluwarsa kunci untuk mobil di konsol Tailscale |
| Alamat tidak menjawab lagi | IP Tailscale mobil berubah | Perbarui alamat di Lanjutan (ini menghapus sesi) |
| Halaman terbuka, kontrol gagal | Preset halaman salah, atau mobil tidak menyediakan endpoint itu | Coba `/vehicle-control.html`; periksa versi Overdrive |
| `ERR_CLEARTEXT_NOT_PERMITTED` | Build aplikasi lama | Perbarui BYD Open Remote (1.3.2+) |
| Lambat / tersendat | Tailscale meneruskan lewat relay (DERP), bukan jalur langsung | Periksa `tailscale status` di ponsel; kedua perangkat di belakang NAT ketat adalah hal normal |

---

## Catatan keamanan

- Tailscale adalah WireGuard: halaman Overdrive yang memakai HTTP biasa tetap berjalan **di dalam
  tunnel terenkripsi**. Jangan melakukan port forwarding port 8080 di router Anda.
- Jaga ACL Tailscale tetap sempit: hanya perangkat Anda sendiri, hanya port 8080 mobil.
- Perlakukan kode akses seperti kunci mobil. Ganti di Overdrive jika mungkin bocor.
- Pengawasan saat parkir merekam gambar kamera di mobil; periksa aturan privasi setempat sebelum
  mengaktifkannya di tempat umum.
