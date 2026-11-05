# CSearch Revamped

Circle anything on your Android screen to search it with Google Lens image search, translate it, or copy its text. You don't need the Google app. CSearch Revamped also identifies songs playing around you, for free and without an account or the Shazam app.

## Features

- **Circle to search.** Freeze the screen, then circle or scribble over anything to search it right away with Google Lens on the web. The results open in a sheet inside the app. A tap only highlights an area, and accidental flicks or jitters are ignored, so a misclick never starts a search. You'll feel a light vibration when you touch down and when a selection locks in.
- **Live translate.** The app's text is replaced in place, at the same position and size and (on Android 14+) in the same colors, and you can keep scrolling while new text is translated as it appears. Like Circle to Search, the original text is replaced right where it is: on photos and gradients the background is rebuilt behind the letters (inpainting), and the translation matches the original's size, weight, colour, line spacing and alignment. Text the app can't get from Android (many web pages, text inside images) is read from the screen with on-device OCR. While you scroll, the translations step aside; when you stop, the new position is translated. Tap the language on the floating bar to switch languages, › to tuck the bar into a tab on the screen edge, or ✕ to stop. Translation runs on your device with ML Kit, so it's free and works offline. Download languages ahead of time (or all of them at once) under **Offline languages**, about 30 MB each.
- **Text.** Copy, edit, search or translate the text in the selection.
- **Share.** Send the cropped selection to any app.
- **Song search.** Hold your phone near the music. The app makes a Shazam-compatible audio fingerprint on the device and looks it up, usually within 4 seconds. Results include the album, year, cover art and links to Shazam, YouTube and Spotify. If Shazam or the Google app is installed, the sheet also offers to hand off to them.

## Ways to start a search

| Trigger | Setup |
| --- | --- |
| Long-press home / gesture bar | Settings → Apps → Default apps → Digital assistant app → **CSearch Revamped** (turn on "Use screenshot") |
| Quick Settings tile | Add the **Screen search** and **Song search** tiles |
| Accessibility button / shortcut | Enable the CSearch Revamped accessibility service |

The accessibility service is what takes screenshots without asking for screen-recording permission on every search. It only captures when you trigger a search. It reads on-screen text only while live translate is on, and that text never leaves your phone.

> **Android 13+ sideloading:** if the accessibility toggle is greyed out ("restricted setting"), open *App info → ⋮ → Allow restricted settings*, then try again.

## Install

Download `csearchrevamped.apk` from the [latest release](../../releases/latest) and install it. Requires Android 11 or newer and Google Play services (for the OCR models).

## Build

```sh
gradle assembleRelease   # Gradle 8.10+, JDK 17, Android SDK 35
```

Or open the project in Android Studio. To build releases automatically, copy `docs/github-actions-build.yml` to `.github/workflows/build.yml`. It signs with the `KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` secrets when they're set, and publishes a release for every `v*` tag.

## Privacy

Screenshots stay in memory and are only uploaded when you search (sent to Google Lens). Live translate happens entirely on your device. Song search never uploads audio: only the fingerprint (peak frequencies, not a recording) is sent to Shazam's recognition service.

Not affiliated with Google or Shazam/Apple.

## License

GPL-3.0. The audio fingerprint generator is a Kotlin port of [SongRec](https://github.com/marin-m/SongRec) by marin-m (GPL-3.0).
