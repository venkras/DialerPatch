# DialerPatch

Xposed module for Phone by Google (`com.google.android.dialer`). It enables Audio Emoji when a server-side flag has turned them off, lets you replace the Audio Emoji sounds with your own, and can unlock built-in call recording.

## Features

- **Enable Audio Emoji** when they are disabled by a server-side flag.
- **Custom sounds**: replace any of the six Audio Emoji sounds with your own file from a settings screen in the module app.
- **No button lock**: the Audio Emoji buttons are blocked only while the on-screen animation plays, not for an extra second after the sound.
- **Call recording without announcement** 

## How it works

The Dialer is obfuscated and class names change between versions, so the module finds the classes it needs at runtime with [DexKit](https://github.com/LuckyPray/DexKit) by searching for stable strings. If a class is not found exactly once, the hook does nothing and writes a line to the log.

Audio Emoji sounds are sent in the caller's voice stream, so the other person hears them and does not need the module.

## Requirements

- Root
- Zygisk or [ReZygisk](https://github.com/PerformanC/ReZygisk)
- LSPosed or fork [Vector](https://github.com/JingMatrix/Vector)
- Phone by Google (`com.google.android.dialer`)

## Installation

1. Install the APK.
2. Grant root access
3. Enable the module in Vector (or LSPosed).
4. Set the scope to `com.google.android.dialer`.
5. Force-stop Phone by Google and open it again.
6. Check Settings in the Phone app.

### Updating

Install the new APK over the old one and force-stop Phone by Google. Releases are signed with a permanent key, so updates install without uninstalling. v1.0 was signed with a different key: if you have v1.0, uninstall it once before installing a newer version.

## Custom sounds

Open the DialerPatch app. There is one block for each emoji with Choose, Play and Reset buttons. The language button switches between English and Russian.

- Any file the Dialer can play works: tested with Ogg and MP3 (including 44.1 kHz stereo). Any length, nothing is trimmed.
- The other person hears your sound too.
- The app copies the file into the Dialer's private storage using root commands, so it asks for root access. After you reinstall the app with a different signature, grant root again and restart the app.
- Reset removes your file and the original sound is used again.

## Call recording (experimental)

The module can unlock the Dialer's built-in call recording in countries where it is disabled. The Dialer's own voice announcement that recording has started is not removed.

Recording laws differ between countries, and many require the consent of the other person. You are responsible for how you use this feature.

## Tested on

| Dialer version | ROM | Android |
|---|---|---|
| 241.0.991235506-publicbeta | OxygenOS 16 | 16 |
| 240.0.986973448-pixel | PixelOS | 13 |

Other versions are untested. If the Dialer updates and a hook stops working, the module searches for the classes again on the next launch.

## Troubleshooting

Check the log:

```sh
su -c "logcat -d | grep -E 'DialerPatch|SonicHook|NoLockHook|RecordingHook'"
```

Log of the module app (custom sounds screen):

```sh
su -c "logcat -d -s DialerPatchApp"
```

If nothing shows up, make sure the module is enabled, the scope includes `com.google.android.dialer`, and you force-stopped the Dialer after enabling it.

## Building

```sh
./gradlew assembleDebug
```

The APK ends up in `app/build/outputs/apk/debug/`. GitHub Actions builds a signed release APK on every push; the signing key is stored in repository secrets.

## Disclaimer

This module is experimental and provided as is, without warranty of any kind. It changes the behavior of a third-party app, so use it at your own risk. The author is not responsible for any damage, data loss, or account restrictions that may result from using it. Not affiliated with or endorsed by Google.
