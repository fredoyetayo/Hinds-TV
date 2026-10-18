#!/bin/bash
# Generates small MPEG-TS test streams for the fake portal (needs ffmpeg on the host).
# 1.ts: H.264 + AAC. 2.ts: H.264 + MP2 audio (the codec Fire TV can't decode in hardware;
# exercises the ffmpeg-audio module). 3.ts: MPEG-2 video + MP2, like many SD broadcast feeds.
# vod.mp4: a 3-minute seekable "lesson" for Videos. radio.mp3: audio for Radio.
set -euo pipefail
out=${1:-build/test-streams}
mkdir -p "$out"
in_opts=(-hide_banner -loglevel error -y)
out_opts=(-t 30 -f mpegts) # -t after the inputs: it must limit the output, not one input
ffmpeg "${in_opts[@]}" -f lavfi -i "testsrc2=size=960x540:rate=25" -f lavfi -i "sine=frequency=440" \
  -c:v libx264 -preset veryfast -g 50 -pix_fmt yuv420p -c:a aac -b:a 128k "${out_opts[@]}" "$out/1.ts"
ffmpeg "${in_opts[@]}" -f lavfi -i "smptehdbars=size=960x540:rate=25" -f lavfi -i "sine=frequency=660" \
  -c:v libx264 -preset veryfast -g 50 -pix_fmt yuv420p -c:a mp2 -b:a 192k "${out_opts[@]}" "$out/2.ts"
ffmpeg "${in_opts[@]}" -f lavfi -i "testsrc=size=720x576:rate=25" -f lavfi -i "sine=frequency=880" \
  -c:v mpeg2video -b:v 4M -g 12 -c:a mp2 -b:a 192k "${out_opts[@]}" "$out/3.ts"
ffmpeg "${in_opts[@]}" -f lavfi -i "testsrc2=size=960x540:rate=25" -f lavfi -i "sine=frequency=330" \
  -c:v libx264 -preset veryfast -g 50 -pix_fmt yuv420p -c:a aac -b:a 128k -t 180 -movflags +faststart "$out/vod.mp4"
ffmpeg "${in_opts[@]}" -f lavfi -i "sine=frequency=520:beep_factor=4" -c:a libmp3lame -b:a 128k -t 120 "$out/radio.mp3"
ls -la "$out"
