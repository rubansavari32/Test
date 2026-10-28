# Buds Spatial

An Android app for using **OnePlus Buds 4** in spatial audio mode.

## What it does

- **Spots your buds.** Shows whether OnePlus Buds 4 (or another Bluetooth headset) is connected. No Bluetooth permission is needed.
- **Shows spatial audio status live.** Reads Android's `Spatializer` API to check whether the phone can spatialize to the buds, whether spatial audio is turned on, whether 5.1 and stereo content will be spatialized, and whether a head-tracking sensor is available.
- **Helps you turn it on.** Gives step-by-step instructions, with shortcuts to Bluetooth settings, Sound settings and the OnePlus / HeyMelody companion app.
- **Spatial test.** Plays a pulsing sound that circles your head:
  - **5.1 (system)** sends real 5.1 audio through Android's spatializer, the same path movies take, and uses head tracking when it's available.
  - **Binaural (app)** is the app's own head model (time and level differences between your ears, plus a duller sound from behind). It works on any phone, including ones older than Android 12L.
- **Spatializes Spotify.** A background service adds 3D headphone sound to Spotify and other music apps that support equalizer apps. It attaches Android's virtualizer (in binaural mode) to the audio session the app announces. You can adjust the strength. You can also pick Buds Spatial as Spotify's equalizer under Settings → Playback → Equalizer. Global mode is an experimental option for apps that don't announce a session.
- **Plays your own media.** Pick an audio or video file and play it with spatialization turned on. Multichannel files (5.1 / 7.1) keep all their channels. The app shows whether the track is being spatialized.

## Limits

Android only lets the user switch the system spatializer on or off (in Settings). Apps can't flip that switch themselves. The buds' own spatial and head-tracking modes use a private OnePlus Bluetooth protocol, and only OnePlus's own settings or HeyMelody can control them. For other apps' music, Android only allows platform audio effects to be attached to the sessions those apps announce. So the Spotify feature uses your phone's built-in virtualizer, and how good it sounds depends on the phone. This app finds those switches, shows what's active and lets you test it. It doesn't replace them.

## Requirements

- Android 8.0 or later (Android 12L or later for system spatial audio, Android 13 or later for head-tracking status)
- OnePlus Buds 4, or any Bluetooth headset

## Build

```sh
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Every push runs the **Build APK** GitHub Actions workflow. You can download the APK from that run's artifacts and install it on your phone.
