#!/usr/bin/env bash
# Component allowlist shared by Android and host verification builds.
# No GPL/nonfree components, network protocols, or host-library autodetection.

CCPLAYER_FFMPEG_DEMUXERS="mov,matroska,avi,mxf,mpegts,mpegps,mpegvideo,m4v,mp3,wav,aiff,caf,flac,ogg,apv,image2,rawvideo,asf,flv,nut,aac,ac3,eac3,dts,h264,hevc,vc1,dv"
CCPLAYER_FFMPEG_MUXERS="mov,mp4,matroska,apv,image2,rawvideo,null,wav,aiff,caf,avi,nut"
CCPLAYER_FFMPEG_PARSERS="h264,hevc,vp8,vp9,av1,mpeg4video,mpegvideo,prores,apv,dnxhd,aac,ac3,dca,flac,mpegaudio,mjpeg,png,vc1"
CCPLAYER_FFMPEG_VIDEO_DECODERS="prores,prores_raw,dnxhd,cfhd,ffv1,apv,h264,hevc,vp8,vp9,av1,mpeg1video,mpeg2video,mpeg4,msmpeg4v1,msmpeg4v2,msmpeg4v3,h263,h263i,wmv1,wmv2,wmv3,vc1,theora,mjpeg,jpeg2000,png,exr,dpx,tiff,gif,bmp,webp,rawvideo,dvvideo,hap,huffyuv,ffvhuff,utvideo,lagarith,v210,v410,r10k,r210,v308,v408,y41p,rv10,rv20,rv30,rv40,vp6,vp6f,vp7,cinepak,indeo2,indeo3,indeo4,indeo5"
CCPLAYER_FFMPEG_AUDIO_DECODERS="aac,ac3,eac3,mp3,flac,alac,vorbis,opus,dca,truehd,mlp,wmapro,wmav1,wmav2,cook,ra_144,ra_288,sipr,amrnb,amrwb,ape,tta,wavpack,shorten,mpc7,mpc8,pcm_s16le,pcm_s16be,pcm_s24le,pcm_s24be,pcm_s32le,pcm_s32be,pcm_f32le,pcm_f32be,pcm_f64le,pcm_f64be,pcm_u8,pcm_mulaw,pcm_alaw,pcm_s16le_planar,adpcm_ms,adpcm_ima_wav,dvaudio"
CCPLAYER_FFMPEG_ENCODERS="prores_ks,dnxhd,ffv1,liboapv,rawvideo,wrapped_avframe,pcm_s16le,pcm_s24le,pcm_s24be,pcm_s32le,aac,flac,alac,png,exr,dpx,tiff,mjpeg,dvvideo,ffvhuff,huffyuv,utvideo,v210,v410"
CCPLAYER_FFMPEG_BSFS="aac_adtstoasc,h264_mp4toannexb,hevc_mp4toannexb,extract_extradata"

CCPLAYER_FFMPEG_COMPONENT_ARGS=(
  "--enable-protocol=file,pipe"
  "--enable-demuxer=${CCPLAYER_FFMPEG_DEMUXERS}"
  "--enable-muxer=${CCPLAYER_FFMPEG_MUXERS}"
  "--enable-parser=${CCPLAYER_FFMPEG_PARSERS}"
  "--enable-decoder=${CCPLAYER_FFMPEG_VIDEO_DECODERS},${CCPLAYER_FFMPEG_AUDIO_DECODERS}"
  "--enable-encoder=${CCPLAYER_FFMPEG_ENCODERS}"
  "--enable-bsf=${CCPLAYER_FFMPEG_BSFS}"
)
