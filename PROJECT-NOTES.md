# PW Controller — source project v1.0.10

Project ini merupakan hasil modifikasi dari `pwcontroller_scaffolding.zip` yang diberikan pengguna.

## Status penting

- Source berhasil dikompilasi sebagai APK release dan lolos `lintDebug` pada 24 Oktober 2026.
- Overlay dapat ditampilkan dan sudah dibagi menjadi beberapa window kecil, bukan satu window layar penuh.
- **Input ke Pixel Worlds belum terverifikasi bekerja.** Pada pengujian pengguna di Android 13, tombol virtual terlihat tetapi tombol asli di dalam Pixel Worlds tidak ikut tertekan. Jangan menganggap bagian controller/input sudah selesai atau production-ready.
- Tidak ada pengujian instrumen pada perangkat fisik yang dilakukan di lingkungan build.

## Perubahan utama dari scaffolding

1. Perbaikan resource dan konfigurasi build agar project dapat dikompilasi.
2. Target Android 13 (`targetSdk 33`), `minSdk 24`, dan filter ARM64. Aplikasi tidak memiliki library native `.so`.
3. Overlay layar penuh diganti menjadi lima window kecil: kontrol kiri, kontrol kanan, Start/Select, toggle, dan indikator edit.
4. Perbaikan visibility overlay, restore tombol toggle, rotasi, edit mode, dan pemuatan profil.
5. Percobaan implementasi gerakan D-pad/analog melalui `AccessibilityService.dispatchGesture` dan profil posisi native Pixel Worlds. Bagian ini masih memerlukan debugging pada perangkat.

## Bagian yang perlu didebug selanjutnya

- `app/src/main/java/com/pwcontroller/service/InputAccessibilityService.kt`
- `app/src/main/java/com/pwcontroller/util/GestureDispatcher.kt`
- `app/src/main/java/com/pwcontroller/service/OverlayService.kt`
- `app/src/main/java/com/pwcontroller/util/PixelWorldsDefaults.kt`

Periksa langsung pada perangkat: status layanan Accessibility, nilai balik dan callback `dispatchGesture`, window foreground penerima gesture, koordinat piksel hasil konversi, dan kemungkinan game mengabaikan gesture aksesibilitas. Koordinat bawaan di `PixelWorldsDefaults.kt` hanya diestimasi dari satu screenshot landscape dan dapat berbeda pada perangkat lain.

## Build

Persyaratan:

- JDK 17
- Android SDK Platform 34
- Android SDK Build-Tools 34.0.0
- `ANDROID_HOME` atau `sdk.dir` pada `local.properties` menunjuk ke SDK lokal

Perintah:

```bash
./gradlew assembleRelease
./gradlew lintDebug
```

APK hasil Gradle berada di `app/build/outputs/apk/release/` dan belum ditandatangani.

## Hal yang sengaja tidak disertakan

- Kunci `.jks`, password, dan kredensial penandatanganan.
- `local.properties` dan path SDK mesin pembuat.
- Folder `build`, `.gradle`, cache dependensi, APK lama, dan Android SDK.

Gunakan kunci penandatanganan yang sama dengan instalasi lama jika APK baru akan dipasang sebagai update. Simpan kunci tersebut di luar project dan jangan membagikannya.

## v1.0.4 (belum dikompilasi/diuji di perangkat)

- Fix: `canPerformGestures="true"` ditambahkan di accessibility config (penyebab dispatchGesture tidak mengirim apa pun). `packageNames` dihapus.
- Baru: backend Shizuku (`ShizukuInjector`, `ShizukuDispatcher`) — sentuhan asli, hold mulus, multi-touch. Dipakai otomatis bila Shizuku jalan + izin diberikan; fallback ke Accessibility.
- Accessibility hold sekarang memakai continued stroke (satu jari terus menempel), bukan tap berulang.
- Dependensi baru: dev.rikka.shizuku api/provider 13.1.5, hiddenapibypass 4.3.
- Tes: `adb logcat -s PWController`

## v1.0.5 (belum dikompilasi/diuji di perangkat)

- Default dipangkas: kiri, kanan, lompat (ATAS/✕), pukul (○), analog, + hotbar (Slot 1 = Select, 2 = △, 3 = Start).
- L1/L2/R1/R2, □, dan BAWAH tidak punya tombol asli di Pixel Worlds: disembunyikan dan tidak mengirim input.
- Window Start/Select dipindah ke atas hotbar (offset 90dp) supaya tap hotbar tidak jatuh ke overlay sendiri.
- Profil lama di database dimigrasi otomatis (placeholder dibuang, koordinat kustom dipertahankan).
- Catatan: wizard kalibrasi (CalibrationWizard) masih menampilkan tombol lama.

## v1.0.6 (belum dikompilasi/diuji di perangkat)

- Baru: kursor virtual + touchpad (`overlay_cursor_pad.xml`). Geser jari di pad = gerak kursor (relatif, ada akselerasi). Tap singkat di pad = klik (tapToClick). Tombol "Klik" = tap; dengan Shizuku, ditahan = jari tetap menempel, dan geser pad sambil menahan = drag.
- Jendela kursor NOT_TOUCHABLE dan alpha 0.7 (Android 12+ memblokir sentuhan lewat overlay beralpha > 0.8). Offset notch diukur lewat getLocationOnScreen.
- Klik diabaikan bila kursor berada di atas window overlay sendiri (mencegah loop).
- Pad bisa digeser di Mode Edit; ikut tersembunyi lewat tombol toggle overlay.

## v1.0.7 (24 Oktober 2026)

- Versi dinaikkan menjadi `versionCode 8` / `versionName "1.0.7"`; `minSdk 24` dan `targetSdk 33` tetap.
- `MainActivity.kt`: panel Shizuku direfresh di `onResume()`, status paket/binder/izin/init ditampilkan, indikator input aktif ditambahkan, aksi buka Shizuku dan minta izin ditambahkan, serta listener binder dan izin didaftarkan/dilepas dengan `try/catch`.
- `activity_main.xml`: kartu Material Shizuku ditambahkan di antara kartu game dan tombol utama; constraint tombol utama disesuaikan.
- `strings.xml`: seluruh teks panel baru ditambahkan dalam bahasa Indonesia.
- `colors.xml`: warna status hijau, kuning, dan merah ditambahkan.
- Package Pixel Worlds `com.kukouri.wizworld` diverifikasi sesuai listing resmi Google Play (https://play.google.com/store/apps/details?id=com.kukouri.wizworld); tidak ada perubahan package yang diperlukan.
- Validasi: `./gradlew assembleDebug` berhasil tanpa error kompilasi. Tidak ada kode lama yang perlu diperbaiki.
- `OverlayService.kt`, `GestureDispatcher.kt`, `ShizukuDispatcher.kt`, `ShizukuInjector.kt`, dan logika tombol lain tidak diubah untuk pekerjaan ini.
- Pengujian UI/status pada perangkat fisik tetap diperlukan.

## v1.0.8 (24 Oktober 2026)

- Versi dinaikkan menjadi `versionCode 9` / `versionName "1.0.8"`; `minSdk 24`, `targetSdk 33`, dan dependensi yang sudah ada dipertahankan.
- `MainActivity.kt` dan `activity_main.xml` didesain ulang dengan Material 3, `NestedScrollView`, edge-to-edge, window insets, kartu 20dp, serta skala spasi 8/12/16/24dp.
- Tema `DayNight` mengikuti sistem dengan palet terang/gelap, tipografi baru, target sentuh minimum 48dp, dan tata letak tombol alat yang dapat membesar mengikuti teks/font.
- Header memuat versi dari `PackageInfo` dengan fallback `BuildConfig`; aksi Simpan Profil dan Muat Profil dipindahkan ke menu overflow tanpa menghilangkan perilakunya.
- Tombol hero 64dp memakai gradient XML dan berubah menjadi Mulai Main, Hentikan Overlay, atau Selesaikan Persiapan. Pilihan terakhir membuka, menggulir, dan menyorot masalah persiapan pertama.
- Kartu Persiapan memiliki empat status Shizuku (belum terpasang, belum berjalan, izin belum diberikan, siap), detail kegagalan inisialisasi, izin overlay, Aksesibilitas opsional, dan status instalasi Pixel Worlds/Play Store. Kartu menciut menjadi Semua siap dan dapat dibuka kembali.
- Banner kuning Mode Edit, chip input aktif, tiga alat tonal (Edit, Kalibrasi, visibilitas overlay), ikon vektor, fade status, `AutoTransition`, dan ripple baris ditambahkan tanpa dependensi baru.
- Peluncuran game, intent `OverlayService`, kalibrasi, profil, izin overlay/Aksesibilitas/notifikasi, dialog pertama, listener Shizuku, dan refresh `onResume` dipertahankan. `ShizukuInjector` dan `InputAccessibilityService.getInstance()` tetap digunakan.
- `OverlayService.kt`, `GestureDispatcher.kt`, `ShizukuDispatcher.kt`, `ShizukuInjector.kt`, `PixelWorldsDefaults.kt`, dan `AndroidManifest.xml` tidak diubah.
- Generator `BuildConfig` bawaan AGP diaktifkan untuk fallback versi; tidak ada dependensi baru.
- Validasi: `./gradlew assembleDebug` berhasil pada 24 Oktober 2026. Dua perbaikan build yang diperlukan adalah penetapan ikon overflow melalui API Toolbar dan pengaktifan `BuildConfig`.
- Penampilan pada perangkat nyata, overlay, injeksi Shizuku, dan input ke Pixel Worlds belum diuji; kompilasi tidak membuktikan fungsi input.

## v1.0.9 (24 Oktober 2026)

- Versi dinaikkan menjadi `versionCode 10` / `versionName "1.0.9"`; `minSdk 24`, `targetSdk 33`, dan dependensi tetap.
- Sesuai pilihan pengguna, wizard kalibrasi dihapus: `CalibrationActivity`, `CalibrationWizard`/`CalibrationTester`, activity pada manifest, layout, dan drawable khusus kalibrasi disingkirkan.
- Alat Kalibrasi di halaman utama diganti **Reset Preset** dengan dialog konfirmasi. Reset memulihkan mapping default dan posisi window tanpa menghapus profil tersimpan lain.
- Preset Pixel Worlds dinormalkan menjadi tujuh target nyata: kiri, kanan, lompat, pukul, Slot 1, Slot 2, dan Slot 3. Analog memakai ulang target kiri/kanan.
- D-pad Atas dihapus karena menduplikasi lompat; D-pad Bawah, Square, L1/L2/R1/R2 dihapus karena tidak memiliki aksi Pixel Worlds yang sesuai.
- Layout overlay disederhanakan: kiri/kanan + analog, Lompat/Pukul, hotbar Slot 1–3, touchpad/kursor, dan tombol PW. Label tombol tidak lagi memakai istilah PS2 yang menyesatkan.
- Semua profil lama dinormalkan ke mapping bersih saat dimuat; nama, skala, alpha, dan timestamp profil dipertahankan.
- Mode Edit sekarang membiarkan sentuhan jatuh ke parent yang dapat diseret. Offset grup kiri, kanan, hotbar, touchpad, dan toggle disimpan melalui `PreferenceManager`, dipulihkan saat window dibuat ulang, dan dibersihkan oleh Reset Preset.
- Validasi: `./gradlew assembleDebug assembleRelease` berhasil pada 24 Oktober 2026. APK release ditandatangani dengan sertifikat update yang sama dan lolos verifikasi v2/v3, zipalign, serta integritas ZIP.
- Tampilan perangkat, koordinat native Pixel Worlds, overlay, injeksi Shizuku/Accessibility, serta input game tetap harus diuji pada perangkat fisik.

## v1.0.10 (24 Oktober 2026)

- Versi dinaikkan menjadi `versionCode 11` / `versionName "1.0.10"` berdasarkan screenshot dan laporan pengguna; `minSdk 24`, `targetSdk 33`, serta dependensi tetap.
- Hotbar overlay Slot 1–3 dan window center dihapus. Hotbar asli Pixel Worlds kini dapat disentuh langsung tanpa tertutup kontrol tiruan.
- Preset hanya menyimpan empat target: kiri, kanan, lompat, dan pukul. Mapping profil lama kembali dinormalkan.
- Tombol Klik terpisah dihapus. Touchpad menjadi satu area: geser untuk menggerakkan kursor, ketuk cepat untuk klik, serta tahan 300 ms lalu geser untuk drag/pointer hold melalui Shizuku.
- Ukuran D-pad, analog, tombol aksi, touchpad, dan label PW diperkecil untuk mengurangi area game yang tertutup.
- `ShizukuDispatcher`: tap diubah menjadi urutan down/move/up selama 140 ms; hold mendapat keep-alive move setiap 33 ms dan runnable keep-alive dibatalkan saat hold berhenti/reset.
- `GestureDispatcher`: durasi tap fallback dinaikkan menjadi 140 ms dan kelanjutan hold diubah dari 400 ms menjadi 5000 ms untuk mengurangi sambungan gesture yang terasa patah.
- Analog memakai hysteresis masuk 0,30 dan keluar 0,16 agar arah tidak cepat berhenti/mulai di sekitar titik tengah.
- Log backend input, tap tombol, klik kursor, dan klik yang diblokir karena berada di atas overlay ditambahkan untuk diagnosis `adb logcat -s PWController`.
- Validasi: `./gradlew assembleDebug assembleRelease` berhasil pada 24 Oktober 2026. APK release lolos tanda tangan v2/v3, zipalign, dan pemeriksaan integritas; sertifikat sama dengan update sebelumnya.
- Belum ada pengujian perangkat fisik pada revisi ini; keberhasilan build tidak membuktikan tap, drag, multi-touch, atau gerakan game sudah bekerja.

## v1.0.11 (24 Oktober 2026)

- Versi dinaikkan menjadi `versionCode 12` / `versionName "1.0.11"`; `minSdk 24`, `targetSdk 33`, serta dependensi tetap.
- **Fase 0 — Diagnostik**: Logging komprehensif ditambahkan (OverlayService, ShizukuInjector):
  - Tiap MotionEvent di overlay: action, pointerId, deviceId, eventTime; ACTION_CANCEL log ms sejak injeksi terakhir.
  - Saat overlay dibuat: log ukuran layar px, density, rect tiap window overlay, px tiap target native, dan tandai bila target berada di dalam rect overlay (±40dp).
  - ShizukuInjector log deviceId injeksi (0) vs deviceId touchscreen asli (InputDevice.getDeviceIds + SOURCE_TOUCHSCREEN).
- **Fase 1a — Jump/Punch Press-Follow**: `pressFollow` di InputDispatcher (Shizuku & Accessibility). DOWN di ACTION_DOWN, UP di UP/CANCEL dengan **minimum 40ms hold** (delay UP bila dilepas terlalu cepat). Satu slot tetap per tombol, tidak ada tumpukan pointer.
- **Fase 1b — Kiri/Kanan No-Gap**: Ganti arah mulai hold baru **sebelum** stop hold lama; hysteresis 0.30/0.16 dipertahankan.
- **Fase 1c — Cached Dispatcher**: `cachedDispatcher` diinisialisasi sekali di `initializeDispatcher()`; `ShizukuPermissionListener` push update saat izin berubah. `getGestureDispatcher()` O(1) tanpa I/O. Hapus `scope.launch` dari jalur tombol.
- **Fase 1d — Touchpad**: Tap-to-click **55ms** (pressFollow), ambang tahan **150ms** (dari 300ms). Kursor via `translationX/Y` (bukan updateViewLayout). `pointerMove` throttle **16ms** (1×/frame).
- **Fase 2 — DeviceId Eksperimen**: Disiapkan struktur (MultiTouch sudah pakai ACTION_POINTER_DOWN/UP, pointerId ≥10 siap), tapi **belum diterapkan** — menunggu log Fase 0 membuktikan CANCEL tepat setelah injeksi.
- **Fase 3 — Tata Letak**:
  - Posisi vertikal: `bottomOffsetPx = screenHeight*(1-0.87) + 40dp + 16dp` (di bawah target native, tidak menutupi).
  - Satu kontrol gerak: Kiri/Kanan **64dp** berdampingan. Analog **opsional, default GONE**.
  - Lompat & Pukul **80dp bulat** (`circular_button_selector.xml` baru).
  - Touchpad **120×88dp**, jarak ≥24dp dari Lompat (aktual ~164dp).
  - Area game tertutup ≤30% lebar; alpha default **0.55** (rentang 0.5–0.6); hotbar asli tidak tertutup.
  - Reset Preset memulihkan posisi dari rumus di atas.
- File diubah: OverlayService.kt, ShizukuDispatcher.kt, GestureDispatcher.kt, InputDispatcher.kt, ShizukuInjector.kt, PixelWorldsDefaults.kt, overlay_left_controls.xml, overlay_right_controls.xml, circular_button_selector.xml (baru), build.gradle.kts.
- **Build**: Source lengkap; build penuh (assembleDebug/assembleRelease/lintDebug) memerlukan build-tools x86_64 (lingkungan ARM64 ini hanya punya aapt2 x86_64 — kegagalan arsitektur, bukan kode). Lihat V1.0.11-CHANGES.md untuk rincian.

## v1.0.12 (24 Oktober 2026)

- Versi dinaikkan menjadi `versionCode 13` / `versionName "1.0.12"`; `minSdk 24`, `targetSdk 33`, serta dependensi tetap.
- **Perbaikan kompilasi & bug scaffolding**:
  1. **OverlayService.kt**: Tambah import `ShizukuInjector` dan `PixelWorldsDefaults` (dipakai tanpa import).
  2. **GestureDispatcher.kt**: Ganti `mutableMap<...>()` → `mutableMapOf<...>()`; beri guard `Build.VERSION.SDK_INT >= O` untuk `StrokeDescription` 4-argumen (`willContinue`) dan `continueStroke`; di API < 26 pakai stroke biasa 140 ms. Terapkan juga di `PressFollowSession`.
  3. **Kursor (OverlayService)**: Jendela kursor jadi satu window `MATCH_PARENT` dengan `FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS`, alpha 0.7. View kursor 36dp digeser lewat `translationX/Y`. Offset window diukur via `getLocationOnScreen` agar koordinat kursor = koordinat injeksi (handle notch/cutout). Hapus `cursorOffX/Y` hardcode.
  4. **Touchpad posisi horizontal**: Geser dari xDp=180 ke 224 (jarak ≥24dp dari tombol Lompat/Pukul). Offset Mode Edit lama ditimpa oleh Reset Preset.
  5. **Pemilihan dispatcher (`initializeDispatcher`)**:
     - Panggil `ShizukuInjector.init()` saat `hasPermission()` true.
     - Daftarkan `Shizuku.addBinderReceivedListener`, `addBinderDeadListener`, `addRequestPermissionResultListener` (lepas di `onDestroy`, bungkus try/catch).
     - Fallback ke `InputAccessibilityService.getInstance()` lazy bila cachedDispatcher null.
     - Hapus pengecekan Shizuku per sentuhan.
  6. **Hapus analog sepenuhnya**: Blok `FrameLayout layoutLeftAnalog` di XML, `setupAnalogStick`, `dispatchAnalogInput`, field `analogTouchId`/`analogDirection`, referensi di `applyProfile`/`setEditMode`/`setVisibility`/`onConfigurationChanged`/`onDestroy`/`resetOverlayLayout`.
  7. **pressFollow race fix (ShizukuDispatcher)**: Bila DOWN datang saat UP tertunda (40 ms) untuk key sama, selesaikan UP tertunda **sekarang** (up + free slot), lalu lakukan DOWN baru. Sebelumnya tap kedua hilang karena `MultiTouch.down()` pada id sama hanya jadi move.
  8. **Diagnostik konflik device**: `@Volatile lastInjectMs` di `ShizukuInjector` (update di `inject()`) dan `GestureDispatcher` (update di `dispatchGesture`). Pakai `InputDispatcher.getLastInjectTime()` untuk log `msSinceLastInjection` pada **SETIAP** `ACTION_CANCEL` & `ACTION_UP` di Kiri, Kanan, Lompat, Pukul, touchpad. Log membedakan CANCEL vs UP.
  9. **Log MOVE (touchpad/hold pulse)**: Bungkus dengan `const val DEBUG_INPUT_LOG = false`. DOWN/UP/CANCEL tetap selalu dicatat.
  10. **Build**: Bump versionCode 13 / versionName 1.0.12. Bersihkan ZIP (tanpa .gradle, app/build, local.properties).
- File diubah: OverlayService.kt, ShizukuDispatcher.kt, GestureDispatcher.kt, InputDispatcher.kt, ShizukuInjector.kt, overlay_left_controls.xml, build.gradle.kts, PROJECT-NOTES.md, V1.0.12-CHANGES.md (baru).
- **Belum diverifikasi**: Kompilasi, lint, input di HP, dugaan konflik deviceId (Fase 2 belum diterapkan).

## v1.0.13 (24 Oktober 2026)

- Versi dinaikkan menjadi `versionCode 14` / `versionName "1.0.13"`; `minSdk 24`, `targetSdk 33`, serta dependensi tetap.
- **Perbaikan kompilasi & perbaikan arsitektur**:
  1. **OverlayService.kt & ShizukuDispatcher.kt**: `private const val DEBUG_INPUT_LOG` dipindahkan ke `companion object` (OverlayService) dan `companion object` ShizukuDispatcher (bukan di body class).
  2. **ShizukuDispatcher.kt**: Tambah import `android.util.Log`.
  3. **OverlayService.kt - Shizuku listener**:
     - Listener Shizuku (`binder received`, `binder dead`, `permission result`) disimpan sebagai field bernama (`shizukuBinderReceivedListener`, `shizukuBinderDeadListener`, `shizukuPermissionResultListener`).
     - Daftarkan **sekali** di `onCreate` via `add*Listener(listener)`.
     - Lepas di `onDestroy` dengan instance yang sama: `removeBinderReceivedListener(listener)`, dst. Hapus pemanggilan `remove*Listener()` tanpa argumen.
  4. **Pisahkan pemilihan backend dari listener**:
     - `initializeDispatcher()` hanya mendaftarkan listener, lalu memanggil `refreshBackend()`.
     - `refreshBackend()` idempoten: **pakai ulang `ShizukuDispatcher` yang sudah ada** (jangan buat `HandlerThread` baru), `init()` bila izin ada, kalau tidak pakai `GestureDispatcher`.
     - Panggil `refreshBackend()` dari: `onCreate`, binder received/dead, permission result (`grantResult == PERMISSION_GRANTED`).
  5. **getGestureDispatcher()**: Bila `cachedDispatcher` null, coba `InputAccessibilityService.getInstance()` lazy (hanya cek null, tanpa binder call) dan cache hasilnya.
  6. **createCursorWindow**: View kursor `Gravity.CENTER` di container, translasi awal `translationX/Y = 0` (pusat container = pusat layar). Kursor tampil di tengah layar saat start. `moveCursorTo` disederhanakan: `translationX = cursorX - screenWidth/2f`.
  7. **Log tombol (Kiri/Kanan/Lompat/Pukul)**: Bungkus `Log.d("Btn ...")` dengan syarat `action != ACTION_MOVE || DEBUG_INPUT_LOG` (sama seperti touchpad).
  8. **Build**: Bump `versionCode 14` / `versionName "1.0.13"`.
- File diubah: OverlayService.kt, ShizukuDispatcher.kt, GestureDispatcher.kt, InputDispatcher.kt, ShizukuInjector.kt, overlay_left_controls.xml, build.gradle.kts, PROJECT-NOTES.md, V1.0.13-CHANGES.md (baru).
- **Belum diverifikasi**: Kompilasi, lint, input di HP, dugaan konflik deviceId.

## v1.0.14 (24 Oktober 2026) — perbaikan "Shizuku tidak berfungsi"

- Versi dinaikkan menjadi `versionCode 15` / `versionName "1.0.14"`; `minSdk 24`, `targetSdk 33`, serta dependensi tetap.
- **Source v1.0.13 ternyata TIDAK bisa dikompilasi**: `Shizuku.OnRequestPermissionResultListener` bertanda `(requestCode: Int, grantResult: Int)`, bukan `(granted: Boolean, requestCode: Int)`. Error: `OverlayService.kt:203 Type mismatch: inferred type is Int but Boolean was expected`. Jadi APK yang berjalan di HP bukan dari source ini.
- **Perbaikan inti**:
  1. Listener izin Shizuku dibetulkan (`requestCode == REQUEST_CODE && grantResult == PERMISSION_GRANTED`).
  2. `ShizukuInjector` sekarang melaporkan error yang bisa dibaca, mencoba beberapa signature `injectInputEvent`, membedakan `SecurityException`, menyimpan counter `injectOk/injectFail`, dan mendukung mode tes `WAIT_FOR_FINISH`.
  3. **Kalibrasi target per tombol** (baru): Mode Edit → tahan tombol virtual > 0,6 dtk → ketuk tombol asli di Pixel Worlds → posisi disimpan dan dipakai untuk injeksi. Ini menggantikan asumsi koordinat hard-code.
  4. **Joystick drag** (baru): `startDragHold()` pada kedua backend — jari turun di joystick lalu digeser ke kiri/kanan (ramp 90 ms). Unity hanya bereaksi pada drag, tap tidak lakukan apa-apa.
  5. **No-gap direction change**: jari baru ditekan sebelum jari lama dilepas (perbaikan `releaseHolds()` yang mematikan semua hold).
  6. **Stray UP** diperbaiki: runnable `pressFollow` tertunda dibatalkan sebelum slot dibebaskan.
  7. **DiagnosticsLogger** (baru): menulis `pwcontroller-diag.txt` ke `/sdcard/Android/data/com.pwcontroller/files/` dan ke Download bila diizinkan.
  8. **Menu "Tes Injeksi (Shizuku)"** (baru): menyuntik tap di tengah layar dan menampilkan hasil nyata (`down=true/false`, pesan error).
  9. Rotasi layar: service menerima `configChanges`, window overlay dibangun ulang, thread injeksi dilepas (`release()`).
  10. `ButtonMapping` menambah `dragDx/dragDy` (migrasi profil lama tidak menghapus nilainya).
- **Build**: `./gradlew assembleDebug` berhasil di host ARM64 dengan `android.aapt2FromMavenOverride` (aapt2 x86_64 dijalankan lewat qemu-user-static).
- **Belum diverifikasi di perangkat**: koordinat preset belum diukur pada HP ini dan status Shizuku di HP belum bisa dibaca (device bridge tanpa shell/screenshot). Setelah APK terpasang: pakai menu **Tes Injeksi** untuk melihat apakah injeksi diterima sistem, lalu kalibrasi target dengan tombol Mode Edit.

