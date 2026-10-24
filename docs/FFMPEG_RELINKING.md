# Replace and relink the native FFmpeg libraries

CC Player keeps proprietary code in `libccplayer_ffmpeg.so` and the rest of the app. FFmpeg itself is
packaged separately per ABI as:

- `libavcodec.so`
- `libavformat.so`
- `libavutil.so`
- `libswresample.so`
- `libswscale.so`

OpenAPV (BSD-3-Clause) is statically linked into `libavcodec.so`; there is no hidden `liboapv.so`
runtime dependency. Android uses unversioned SONAMEs so the package loader does not request an
unpackaged `libav*.so.<major>` file.

## Reproduce the supplied build

Use JDK 17, Android SDK 37, NDK `28.2.13676358`, and CMake `3.31.6`:

```bash
./gradlew :media:ffmpeg:buildFfmpegNative :app:assembleDebug
```

The per-ABI outputs are under `media/ffmpeg/build/ffmpeg/`:

- `install/<abi>/`: headers and linked libraries;
- `jniLibs/<abi>/`: the five replaceable APK libraries;
- `config/<abi>/ffmpeg-configure-command.txt`: exact configure invocation;
- `config/<abi>/ffmpeg-config.h` and `ffmpeg-config.mak`: resulting configuration;
- `config/<abi>/*.dynamic.txt`: ELF SONAME/dependency inspection;
- `downloads/`: pinned, hash-verified corresponding source archives.

To modify FFmpeg, edit the pinned source or build flags, retain the no-GPL/no-nonfree policy, and run
the same task with a changed build-input fingerprint. Do not replace the requested codec with a
lower-precision encoder or silently drop alpha.

## Replace the libraries in an existing APK

Android packages are signed, so replacing a library requires re-signing and reinstalling the APK.
The helper updates only the five FFmpeg libraries, leaves proprietary code unchanged, and verifies
the resulting signature:

```bash
export CCPLAYER_KS_PASS='your-local-keystore-password'
export CCPLAYER_KEY_PASS='your-local-key-password'
scripts/replace-ffmpeg-libraries.sh \
  --input app/build/outputs/apk/debug/app-debug.apk \
  --output /tmp/app-relinked.apk \
  --libs-root media/ffmpeg/build/ffmpeg/jniLibs \
  --apksigner "$ANDROID_HOME/build-tools/36.0.0/apksigner" \
  --keystore /path/to/local/debug.keystore \
  --alias androiddebugkey
```

Passwords are read only from environment variables by `apksigner`; they are not written to the
script, repository, APK, or command output. Unset them after use. Production distribution must use
its own protected signing process and must ship the corresponding source/configuration for the exact
modified libraries, not merely the original upstream archives.
