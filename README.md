# Terjemah Korea — terjemahan LIVE Korea → Indonesia (Android)

Buka Weverse (aplikasi atau weverse.io di Chrome) seperti biasa. Begitu kamu berhenti scroll
sebentar (±½ detik), teks Korea di layar otomatis ditimpa kotak terjemahan bahasa Indonesia.
Saat kamu scroll lagi, kotaknya hilang dan muncul lagi di posisi baru ketika layar diam.
Bisa juga dipakai untuk webtoon dan media sosial lain, karena yang dibaca adalah layar.

## Mode online: Gemini (disarankan)

Jauh lebih akurat daripada terjemahan bawaan Weverse maupun mode offline.
- Postingan artis, komentar, balasan, dan chat live dibaca per item, jadi komentar yang berbeda tidak digabung.
- Diterjemahkan dengan gaya bahasa fans Indonesia. Nama, sapaan (oppa, unnie, hyung), dan emoji
  dipertahankan; ㅋㅋㅋ jadi "wkwk".
- Tombol, jumlah like, waktu posting, nama pengguna, dan terjemahan bawaan Weverse dilewati.

Cara pakai:
1. Buka Google AI Studio (tombol ada di aplikasi), login, pilih *Create API key*.
2. Tempel key di aplikasi, lalu tekan **Tes API key**.
3. Model default `gemini-3.5-flash-lite` memakai tier gratis yang punya kuota harian.
   Kalau kuota habis (HTTP 429), aplikasi otomatis memakai mode offline selama 10 menit lalu mencoba lagi.
4. Layar yang sama tidak dikirim ulang (ada cache), jadi kuota lebih hemat.

Catatan: data tier gratis boleh dipakai Google untuk meningkatkan layanannya.

## Mode offline: ML Kit

OCR Korea dan terjemahan Korea → Indonesia berjalan di HP, tanpa internet dan tanpa API key.
Kualitasnya standar. Model terjemahan diunduh sekali (±30 MB).

## Cara mendapatkan APK

1. Buka tab **Actions** di repo ini dan tunggu workflow **Build APK** selesai (±2–5 menit).
2. Klik run yang sudah selesai → **Artifacts** → unduh `TerjemahKorea-apk`.
3. Ekstrak, pindahkan `app-debug.apk` ke HP, lalu install (izinkan "sumber tidak dikenal").

Alternatif: buka folder ini di **Android Studio** → *Build → Build APK(s)*.

## Cara pakai

1. Buka **Terjemah Korea**.
2. Izinkan *tampil di atas aplikasi lain*.
3. Pilih mode Gemini dan isi API key. Tekan juga **Unduh model** sekali sebagai cadangan offline.
4. Tekan **Mulai terjemahan live**. Di dialog Android, pilih **Bagikan seluruh layar** → Mulai.
5. Buka Weverse dan baca. Tombol bulat **번역** dipakai untuk jeda atau lanjut (abu-abu berarti dijeda),
   dan bisa digeser ke mana saja. Untuk berhenti, pakai tombol *Berhenti* di notifikasi.

## Keterbatasan

- **Kotak terjemahan 80% pekat.** Android 12+ memblokir sentuhan yang menembus overlay yang
  lebih pekat dari itu, jadi teks Korea di bawahnya masih samar terlihat. Ini syarat supaya
  kamu tetap bisa scroll.
- Android 14+ meminta izin rekam layar setiap kali terjemahan dimulai. Itu aturan sistem.
- Bagian aplikasi yang memblokir rekam layar (misalnya video tertentu) akan terekam hitam,
  jadi teks di situ tidak bisa diterjemahkan.
- Teks Korea ringkas, terjemahan Indonesia lebih panjang, jadi kotak untuk komentar pendek bisa sempit.
- Video atau GIF yang terus bergerak bisa membuat terjemahan sering muncul-hilang.
  Ketuk 번역 untuk jeda kalau mengganggu.
- Kalau layar diputar, hentikan lalu mulai ulang.

## Struktur kode

```
app/src/main/java/id/terjemah/korea/
├── MainActivity.kt           layar pengaturan, izin, unduh model
├── TranslatorService.kt      rekam layar + logika live (deteksi scroll/diam) + tombol 번역
├── FrameSignature.kt         sidik jari frame untuk deteksi gerak
├── GeminiEngine.kt           mode online: Gemini membaca & menerjemahkan tangkapan layar
├── OfflineEngine.kt          mode offline: ML Kit OCR Korea + terjemahan on-device
├── TranslationOverlayView.kt menggambar kotak terjemahan (tembus sentuhan)
└── TranslationEngine.kt      model data
```

Parameter yang bisa disetel ada di bagian atas `TranslatorService.kt`:
`SETTLE_MS` (lama diam sebelum menerjemahkan), `MOVE_THRESHOLD`, `SETTLE_THRESHOLD`.
