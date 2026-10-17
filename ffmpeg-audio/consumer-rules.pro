# Consumer R8/ProGuard rules for the :ffmpeg-audio module (Media3 FFmpeg audio
# decoder extension). Based on Media3 1.11.1 libraries/decoder_ffmpeg/proguard-rules.txt
# and the FFmpeg entries of libraries/exoplayer/proguard-rules.txt.

# libffmpegJNI.so binds its natives with RegisterNatives() in JNI_OnLoad, looking
# classes and methods up by name: keep native method names (and the classes that
# declare them, e.g. FfmpegLibrary and FfmpegAudioDecoder) and the classes in
# their signatures (e.g. androidx.media3.decoder.SimpleDecoderOutputBuffer).
-keep,includedescriptorclasses class androidx.media3.decoder.ffmpeg.** {
    native <methods>;
}

# This method is called from native code.
-keep,includedescriptorclasses class androidx.media3.decoder.ffmpeg.FfmpegAudioDecoder {
    private java.nio.ByteBuffer growOutputBuffer(androidx.media3.decoder.SimpleDecoderOutputBuffer, int);
}

# Loads the native library and is looked up by name from JNI_OnLoad.
-keep class androidx.media3.decoder.ffmpeg.FfmpegLibrary {
    native <methods>;
}

# DefaultRenderersFactory instantiates the renderer reflectively via
# Class.forName("androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer")
# and getConstructor(Handler, AudioRendererEventListener, AudioSink).
-dontnote androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
-keep class androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer {
    <init>(android.os.Handler, androidx.media3.exoplayer.audio.AudioRendererEventListener, androidx.media3.exoplayer.audio.AudioSink);
}

# This module deliberately omits Media3's (non-functional) ExperimentalFfmpegVideoRenderer;
# DefaultRenderersFactory tolerates its absence (ClassNotFoundException).
-dontnote androidx.media3.decoder.ffmpeg.ExperimentalFfmpegVideoRenderer
