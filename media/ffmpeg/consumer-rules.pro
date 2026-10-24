-keep class com.ccplayer.media.ffmpeg.FFmpegNative { *; }
-keep class com.ccplayer.media.ffmpeg.FFmpegException { *; }
-keepclassmembers class com.ccplayer.media.ffmpeg.FFmpegExportEngine$* {
    public void onProgress(int, long);
    public boolean isCancelled();
}
