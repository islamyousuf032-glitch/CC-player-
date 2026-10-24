-keep class com.ccplayer.media.ffmpeg.FFmpegNative { *; }
-keep class com.ccplayer.media.ffmpeg.FFmpegException { *; }
-keep class com.ccplayer.media.ffmpeg.FFmpegPlayer { *; }
-keep class com.ccplayer.media.ffmpeg.FFmpegImageDecoder { *; }
-keepclassmembers class com.ccplayer.media.ffmpeg.FFmpegExportEngine$* {
    public void onProgress(int, long);
    public boolean isCancelled();
}
