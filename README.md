# MyID Wallet — Android Studio Project

Native Android starter project for an offline-first identity-card wallet.

## Included in this project

- Kotlin + Jetpack Compose UI
- Room / SQLite document metadata database
- Android Keystore-backed AES-256-GCM file encryption
- PIN setup and unlock gate (PBKDF2-HMAC-SHA256 verifier)
- Import front/back document images from Android's document picker
- Encrypted private app file storage
- Swipeable document collection
- 3D front/back card flip
- Favourite cards
- Save a selected card as a PDF
- Share selected front/back/both sides using Android Sharesheet
- Light/dark/system theme setting

## Important security and product limitations

This is a functional starter/MVP, not a certified identity wallet or a completed security-audited product.

- The PIN is an app access gate. Document encryption keys are protected by Android Keystore. A short PIN cannot by itself guarantee protection against all offline attacks.
- The sample does not implement a secure PIN recovery mechanism. Do not forget the PIN.
- PDF exports and files shared to other apps leave the encrypted storage boundary. The receiving app or recipient may retain copies.
- Government document images are stored as user-provided scans. This app does not verify their authenticity and is not a government service.
- Before publishing, add automated tests, robust rate limiting, security review, accessibility review, document redaction, temporary-file lifecycle handling, and verify Android backup exclusion on target devices.
- No cloud backup or network upload is implemented.

## Open and run

1. Install Android Studio.
2. Open this folder (`MyIDWallet`) in Android Studio.
3. Allow Gradle sync to finish.
4. Use JDK 17.
5. Connect an Android device or start an emulator (API 26+).
6. Press Run.

If Android Studio asks to update Gradle or Android Gradle Plugin, first try the supplied versions and only update compatible versions together.

## Package

`com.myid.wallet`

## Main source locations

- `MainActivity.kt` — app entry point and navigation
- `data/` — Room database and repository
- `security/` — PIN verifier and encryption manager
- `ui/` — Compose screens and theme
- `util/PdfExporter.kt` — PDF creation and Android sharing

## Data handling

Original images are encrypted before they are written to the app's private files directory. Room stores document metadata and encrypted file names. The app does not request broad storage permissions; it uses Android's document picker.

Keep the device secure. Uninstalling the app or clearing its data deletes the local wallet files and encryption key.
