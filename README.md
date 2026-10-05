# DialerPatch

Xposed module that enables Audio Emoji in Phone by Google when it's disabled by a server-side flag.

## What it does

Hooks `SonicEnabledFn.isEnabled()` in `com.google.android.dialer` and makes it return `true`. The class name is obfuscated and changes between Dialer versions, so the module finds it at runtime with [DexKit](https://github.com/LuckyPray/DexKit) by searching for the string `com/android/dialer/sonic/impl/SonicEnabledFn`.

Audio Emoji sounds are sent in the caller's voice stream, so the other person doesn't need the module. The sounds and animations are downloaded by the Dialer from Google's servers; the module doesn't touch them.

## Requirements

- Root
- Zygisk or [ReZygisk](https://github.com/PerformanC/ReZygisk)
- LSPosed or fork [Vector](https://github.com/JingMatrix/Vector)
- Phone by Google (`com.google.android.dialer`)

## Installation

1. Install the APK.
2. Enable the module in Vector (or LSPosed).
3. Set the scope to `com.google.android.dialer`.
4. Force-stop Phone by Google and open it again.
5. Check Settings in the Phone app for Audio Emoji.

## Tested on

| Dialer version | ROM | Android |
|---|---|---|
| 241.0.991235506-publicbeta | OxygenOS 16 | 16 |
| 240.0.986973448-pixel | PixelOS | 13 |

Other versions are untested. If the Dialer updates and the hook stops working, the module will search for the class again on the next launch.



## Troubleshooting

Check the log:

```sh
su -c "logcat -d | grep DialerPatch"
```

A working setup logs something like:

```
DialerPatch: DexKit successfully found class: <name>
DialerPatch: Successfully hooked method a() to return true
```

If nothing shows up, make sure the module is enabled, the scope includes `com.google.android.dialer`, and you force-stopped the Dialer after enabling it.

## Building

```sh
./gradlew assembleDebug
```

The APK ends up in `app/build/outputs/apk/debug/`. GitHub Actions builds it too.
## Disclaimer
This module is experimental and provided as is, without warranty of any kind. It changes the behavior of a third-party app, so use it at your own risk. The author is not responsible for any damage, data loss, or account restrictions that may result from using it. Not affiliated with or endorsed by Google.
