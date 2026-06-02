# 📖 Readio — Audiobook App (V1)

Convert documents to audio and listen like Audible, for free, on your Android device.

---

## Features
- **Local account** (email + password, stored securely on-device)
- **Google Sign-In** (requires Firebase setup — see below)
- **Import files** from device storage or Google Drive
- **Supported formats:** PDF, DOCX, TXT, EPUB, RTF
- **Text-to-Speech conversion** using Android's built-in TTS engine (free, offline)
- **Library** with listening progress bar per book
- **Player** with play/pause, seek bar, skip ±15/30s, resume from last position, speed control (0.75× – 2×)

---

## Build in GitHub Codespaces (Recommended — no local install needed)

### Step 1 — Push to GitHub
```bash
git init
git add .
git commit -m "Initial Readio project"
gh repo create readio-app --private --source=. --push
# OR manually create a repo on github.com and push
```

### Step 2 — Open in Codespaces
1. Go to your repo on GitHub
2. Click **Code → Codespaces → Create codespace on main**
3. Wait ~3–5 minutes for the Android SDK to install automatically

### Step 3 — Build the APK
In the Codespaces terminal:
```bash
./gradlew assembleDebug
```
APK will be at:
```
app/build/outputs/apk/debug/app-debug.apk
```

### Step 4 — Download and install on your phone
1. In Codespaces file explorer, right-click the APK → **Download**
2. Transfer to your Android phone (USB / WhatsApp / Google Drive)
3. Enable **Install from unknown sources** in phone settings
4. Open the APK to install

---

## Google Sign-In Setup (Optional — local login works without this)

1. Go to [Firebase Console](https://console.firebase.google.com/)
2. Create a project → Add Android app → package name: `com.audiobookapp`
3. Download `google-services.json`
4. **Replace** `app/google-services.json` with the downloaded file
5. In Firebase Console → Authentication → Sign-in method → Enable **Google**
6. Add your debug SHA-1 fingerprint:
   ```bash
   keytool -list -v -keystore ~/.android/debug.keystore \
     -alias androiddebugkey -storepass android -keypass android
   ```
   Copy the SHA1 → Firebase Console → Project Settings → Your apps → Add fingerprint

---

## EPUB Support Note
The `epublib` library is hosted on JitPack. Add this to your root `build.gradle` repositories block if the build fails:
```groovy
maven { url 'https://jitpack.io' }
```

---

## Project Structure
```
app/src/main/java/com/audiobookapp/
├── data/
│   ├── db/          — Room database, DAOs
│   └── model/       — User, AudioBook entities
├── service/         — AudioPlaybackService (Media3)
├── ui/
│   ├── auth/        — AuthActivity, LoginFragment, RegisterFragment, AuthViewModel
│   ├── converter/   — ConverterFragment, ConverterViewModel
│   ├── library/     — LibraryFragment, LibraryViewModel, AudioBookAdapter
│   └── player/      — PlayerFragment, PlayerViewModel
└── utils/
    ├── SessionManager.kt   — Encrypted session storage
    ├── PasswordUtils.kt    — SHA-256 + salt hashing
    ├── TextExtractor.kt    — PDF/DOCX/TXT/EPUB text extraction
    └── TtsConverter.kt     — Android TTS → WAV file
```

---

## V2 Roadmap (Planned)
- AI-powered clip notes (highlight + annotate sections)
- Bookmarks
- Chapter detection
- Cloud sync
- Voice selection (language/accent)
