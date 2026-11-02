# APK Forge

An Android app for inspecting, editing and re-signing APK files, built in stages.

**Current version: Stage 2.** It can:

- List every installed app (with search, and a switch for system apps)
- Open any `.apk` file from your phone, or use "Open with APK Forge" from a file manager
- Show the app's details: package name, version, minimum and target Android version, size
- Show its permissions, activities, services, receivers and providers
- Check its signature (valid or not, v1/v2/v3, certificate owner, SHA-256 fingerprint)
- Browse the files inside the APK and save any single file
- Save (extract) an installed app's APK
- Re-sign an APK and save it, or re-sign and install it
- **Edit an app:** change its name, icon, version name, version code and package name
- **Clone apps:** a new package name makes a copy that installs next to the original
- **Merge split APKs:** turn a Play Store app installed as several APKs into one APK
- **Open bundles:** `.apks`, `.xapk` and `.apkm` files are merged into one APK automatically

## How to build and install it

You only need to do the setup once.

1. **Install Android Studio** from https://developer.android.com/studio (free). Accept the default options. The first launch downloads the Android SDK, which takes a while.
2. **Unzip** `ApkForge.zip` somewhere easy, such as your Documents folder.
3. In Android Studio choose **File > Open** and select the `ApkForge` folder (the one with `settings.gradle.kts` in it).
4. Wait for **Gradle sync** to finish (progress bar at the bottom). The first time it downloads libraries, so it can take 5 to 15 minutes. If it asks to install a missing SDK platform, click the link it shows.
5. **Connect your phone:**
   - On the phone, open Settings > About phone and tap **Build number** 7 times to unlock Developer options.
   - Go to Settings > Developer options and turn on **USB debugging**.
   - Plug the phone into the PC with a USB cable and tap **Allow** on the phone.
6. Pick your phone in the device dropdown at the top of Android Studio and press the green **Run** button. The app installs and opens on your phone.

**Want an APK file instead?** Use **Build > Build App Bundle(s) / APK(s) > Build APK(s)**. When it finishes, click **locate** in the popup. The file is `app/build/outputs/apk/debug/app-debug.apk`. Copy it to your phone and install it.

### If something goes wrong

- **"Could not resolve io.github..." or any "Could not resolve" error:** check your internet connection, then try **File > Sync Project with Gradle Files**.
- **A library version is not found:** open `gradle/libs.versions.toml` and lower that version number, or send me the exact error.
- **Any red error you don't understand:** copy the full message and send it to me. I couldn't compile this project in my workspace, so the first build is where any small mistakes will show up.

## Things to know

- **Signing key.** APK Forge signs APKs with its own key, stored in `app/src/main/assets/signing/apkforge.p12` (password `apkforge`). Android will not install a re-signed app on top of the original version, because the keys differ. Uninstall the original first.
- **Split APKs.** Many Play Store apps are installed as several APK files. Tap "Merge into one APK" on such an app before saving, editing or installing it.
- **Clones.** Some apps check their own package name or signature (banking apps, many games, apps with Google sign-in) and may close or refuse to log in after editing. That is a protection inside those apps, not a bug in APK Forge.
- **Encrypted bundles.** Newer `.apkm` files from APKMirror can be encrypted and won't open.
- **Use it responsibly.** Edit your own apps, or apps you are allowed to modify. Removing ads, licence checks or paywalls from other people's apps usually breaks their terms of service.

## Roadmap

| Stage | What it adds |
|---|---|
| **1 (done)** | App list, APK details, signature check, file browser, extract, re-sign, install |
| **2 (done)** | Quick edits: app name, icon, version, package name (clone apps). Merge split APKs into one |
| 3 | Full resource editor: decode the APK, browse and edit XML files (manifest, layouts, strings) in a code editor, replace images, rebuild |
| 4 | Code (smali) editor: decompile `classes.dex`, edit with search and syntax highlighting, recompile |
| 5 | Extras: sign with your own key, common patches, translation helper, settings |

Stages 2 and 3 use the [ARSCLib](https://github.com/REAndroid/ARSCLib) library, which reads and writes Android's binary resource files directly on the phone. Stage 4 will use Google's smali/baksmali libraries.

## Project layout

```
app/src/main/java/com/sangam/apkforge/
  MainActivity.kt        switches between screens, handles file pickers
  MainViewModel.kt       app state and actions (open, sign, save, install)
  data/
    AppRepository.kt     installed apps and icons
    ApkInspector.kt      reads manifest info, signature and file list
    ApkSigning.kt        signs APKs (uses Google's apksig library)
    FileOps.kt           copying files, opening the installer
    Models.kt            data classes
  engine/
    ApkEditEngine.java   edits APKs and merges splits (uses ARSCLib, in app/libs)
  ui/
    Components.kt        shared buttons, cards, icon loader
    theme/Theme.kt       colours and shapes
    screens/             Home, Details and Files screens
```
