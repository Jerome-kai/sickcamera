# Latent — the camera, as an Android app

Latent is ImageGenCam rewritten for your phone. The Orange Pi, the SPI screen and the five
buttons are gone; the phone's own camera, screen and touch take their place. What the camera
*did* is all still here: shoot a photo, let an image model redraw it from a prompt, and collect
the results in an album.

## Install it on your phone

1. On the phone, open **https://github.com/Jerome-kai/sickcamera/releases** and pick the newest
   **Latent** release.
2. Tap the `.apk` file to download it, then tap it again to install.
3. Android will ask to allow installs from your browser (or Files app). Allow it for this one
   install; you can switch it back off afterwards.
4. Open **Latent**, work through the short tutorial, and paste your API key on the last page.

Every build installs *over* the previous one, so your prompts, photos and API key survive
updates (see [Signing](#signing) for the one caveat).

Needs Android 8.0 (API 26) or newer, a camera, and an internet connection when a photo is sent
to the model.

## Using it

| You want to… | Do this |
|---|---|
| Take a photo | Tap the big shutter button, or press a volume key |
| Start **Magic mode** | *Hold* the shutter (or hold a volume key) — a vision model invents a funny motif and prompt from what the camera sees; later shots use it |
| Leave Magic mode | Tap the ✕ on the amber pill at the top |
| Pick a prompt | Tap the pill at the top of the viewfinder |
| Pick a model | Slide along the dial at the bottom (the phone version of the camera's physical wheel) |
| See results | Tap the album button. A sparkle on it means new results arrived while you were elsewhere |
| Compare with the original | Open an image, tap **Original** |
| Make a 9:16 story version | Open an image, tap **9:16** |
| Edit prompts, attach a reference image | Settings → Prompts |
| Add or switch models and providers | Settings → Models |
| See photos still being made, retry or dismiss failures | Tap the queue badge at the top right |

Photos are queued and made in the background, so you can keep shooting — and close the app.
A notification tells you when each one is ready. If the phone is offline, photos wait for a
connection instead of failing.

## Setting up a provider

Latent talks to any OpenAI-compatible server. **Settings → Models → Provider setups** has
one-tap recipes for the three setups the camera documented:

| Provider | Needs | Image model |
|---|---|---|
| OpenAI | an OpenAI API key | `gpt-image-2` via `/images/edits` |
| Vercel AI Gateway | a gateway key | `google/gemini-2.5-flash-image` via chat completions |
| SiliconFlow | a SiliconFlow key | `Qwen/Qwen-Image-Edit-2509` via `/images/generations` (reachable from China without a VPN) |

The key is stored encrypted with a key that lives in the phone's hardware keystore. It is
only ever sent to the server whose address you set, and it is never written to a plain file
or a log.

## What carried over, what changed, what is gone

The camera was inventoried feature by feature (316 of them) before this was written. Of those,
147 carry over as they were, 125 are adapted to a phone, and 44 are dropped because they only
existed for the Pi's hardware.

**Dropped, on purpose:** GPIO buttons and pull-ups, the SPI display driver, the hot-shoe flash
trigger, the rotary model wheel's wiring, the PiSugar battery pack, the Wi-Fi setup hotspot and
its on-screen keyboard, nmcli/sudoers, the boot splash and systemd units, the LAN web server
with its QR download codes, and the diagnostics screens. A phone already has a screen, a
network stack, a share sheet and a power button.

**Adapted:** the web app's prompt editor, gallery and settings became native screens; QR
downloads became **Share** and **Save to phone**; the idle-sleep screen is now just Android's
own; the shutter's tap-versus-hold split (hold for Magic mode) is kept, on both the on-screen
button and the volume keys.

**Behaviour that is deliberately better than the camera's** — each of these came out of the
camera's security audit rather than being ported as it was:

- The API key is encrypted at rest. The camera kept it in a plain `.env` that, until the audit,
  one URL could read.
- The key is never forwarded to another host if a server redirects.
- Image links returned by a provider must be `https` and are size-capped before download.
- A wrong key or a refused prompt stops the retry loop at once. The camera retried those for
  hours.
- The model is captured *with the photo*, so turning the dial afterwards cannot change a photo
  already queued. (The camera's documentation promised this; its code did not do it.)
- A photo and its AI result share an id, so **Original** is exact rather than a nearest-timestamp
  guess.
- Portrait photos stay portrait. The camera cropped everything to landscape because its screen
  was landscape.
- Deleting a prompt deletes its reference image; the camera leaked those forever.
- Storage can be inspected and reclaimed (Settings → Storage). The camera never deleted anything.

## Signing

Android only installs an update over an app signed with the **same key**, so the key must not
change between builds. CI signs in one of two ways:

1. **A private key from repository secrets** — used whenever `LATENT_KEYSTORE_BASE64` is set.
2. **`signing/public-fallback.keystore`** — a keystore committed to this repository
   *deliberately*. It is **not a secret**. It exists so that a fresh clone produces APKs that
   update in place without any setup.

The fallback is fine for a personal app, but understand what it means: anyone could build an APK
that Android accepts as an *update* to yours, because they can sign with the same public key. They
would still have to get you to install it. If you ever share the app widely, or just want the
signature to mean something, switch to a private key:

```bash
keytool -genkeypair -keystore latent.keystore -alias latent -keyalg RSA -keysize 2048 \
        -validity 36500          # answer the prompts, remember both passwords
base64 -w0 latent.keystore       # macOS: base64 -i latent.keystore
```

Then add these four **Actions secrets** (repository → Settings → Secrets and variables →
Actions): `LATENT_KEYSTORE_BASE64` (the base64 text), `LATENT_KEYSTORE_PASSWORD`,
`LATENT_KEY_ALIAS` (`latent`) and `LATENT_KEY_PASSWORD`. **Keep `latent.keystore` somewhere
safe**: if you lose it, the next build cannot update the installed app and you would have to
uninstall first, which deletes its data.

Switching from the fallback to a private key is itself a key change, so do it once, early:
uninstall, install the first privately-signed build, and you are set.

## Building

CI does everything: `.github/workflows/android.yml` runs the `:core` tests, builds the release
APK, uploads it as an artifact, and publishes it as a GitHub Release (a *prerelease* when built
from the dev branch).

Locally, only the pure-Kotlin core can be built and tested without an Android SDK:

```bash
cd android/core && ../gradlew test
```

The app module needs the Android SDK (`ANDROID_HOME`) and builds with
`cd android && ./gradlew :app:assembleRelease`.

`core/src/test/resources/golden/python.json` is recorded from the camera's real Python code by
`tools/export_golden.py`; the Kotlin tests assert the port produces byte-identical prompts and
identical normalisation, so any drift from the original fails the build. Re-run the script if the
Python side changes.

## Layout

```
android/
  core/    pure Kotlin, no Android: API clients, stores, retry policy, gallery, geometry
  app/     Compose UI, CameraX, WorkManager, encrypted key storage
  signing/ the public fallback keystore (not a secret)
  tools/   export_golden.py — records the Python camera's behaviour for the tests
```

`core` is an included build substituted into `app` as `io.github.jeromekai.latent:core`, which
is why it can compile and test on a machine that has no Android SDK at all.
