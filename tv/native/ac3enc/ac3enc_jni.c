// Dolby Digital (AC-3) encoder for the player: 16-bit PCM in, one AC-3 frame out.
// Built by build.sh with FFmpeg's own ac3 encoder linked in statically.
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <libavcodec/avcodec.h>
#include <libavutil/channel_layout.h>
#include <libavutil/frame.h>

typedef struct {
    AVCodecContext *ctx;
    AVFrame *frame;
    AVPacket *pkt;
    int channels;
    int64_t pts;
} Enc;

static void enc_free(Enc *e) {
    if (!e) return;
    if (e->pkt) av_packet_free(&e->pkt);
    if (e->frame) av_frame_free(&e->frame);
    if (e->ctx) avcodec_free_context(&e->ctx);
    free(e);
}

JNIEXPORT jlong JNICALL
Java_com_notdxbi_dxbi_tv_Ac3Native_open(JNIEnv *env, jclass cls, jint sampleRate, jint channels, jint bitrate) {
    const AVCodec *codec = avcodec_find_encoder(AV_CODEC_ID_AC3);
    if (!codec) return 0;
    Enc *e = calloc(1, sizeof(Enc));
    if (!e) return 0;
    e->channels = channels;
    e->ctx = avcodec_alloc_context3(codec);
    if (!e->ctx) { enc_free(e); return 0; }
    e->ctx->sample_fmt = AV_SAMPLE_FMT_FLTP;
    e->ctx->sample_rate = sampleRate;
    e->ctx->bit_rate = bitrate;
    // Android's 5.1 order: front left, front right, centre, LFE, back left, back right
    if (channels == 6) av_channel_layout_from_mask(&e->ctx->ch_layout, AV_CH_LAYOUT_5POINT1_BACK);
    else if (channels == 2) av_channel_layout_from_mask(&e->ctx->ch_layout, AV_CH_LAYOUT_STEREO);
    else { enc_free(e); return 0; }
    if (avcodec_open2(e->ctx, codec, NULL) < 0) { enc_free(e); return 0; }
    e->frame = av_frame_alloc();
    e->pkt = av_packet_alloc();
    if (!e->frame || !e->pkt) { enc_free(e); return 0; }
    e->frame->nb_samples = e->ctx->frame_size;
    e->frame->format = e->ctx->sample_fmt;
    e->frame->sample_rate = sampleRate;
    if (av_channel_layout_copy(&e->frame->ch_layout, &e->ctx->ch_layout) < 0 || av_frame_get_buffer(e->frame, 0) < 0) { enc_free(e); return 0; }
    return (jlong)(intptr_t)e;
}

JNIEXPORT jint JNICALL
Java_com_notdxbi_dxbi_tv_Ac3Native_frameSize(JNIEnv *env, jclass cls, jlong handle) {
    Enc *e = (Enc *)(intptr_t)handle;
    return e ? e->ctx->frame_size : 0;
}

// in: frameSize interleaved 16-bit samples per channel (direct buffer). Returns the AC-3 frame's size in out, 0 when none yet, <0 on error
JNIEXPORT jint JNICALL
Java_com_notdxbi_dxbi_tv_Ac3Native_encode(JNIEnv *env, jclass cls, jlong handle, jobject in, jobject out) {
    Enc *e = (Enc *)(intptr_t)handle;
    if (!e) return -1;
    const int16_t *src = (const int16_t *)(*env)->GetDirectBufferAddress(env, in);
    uint8_t *dst = (uint8_t *)(*env)->GetDirectBufferAddress(env, out);
    jlong cap = (*env)->GetDirectBufferCapacity(env, out);
    if (!src || !dst) return -2;
    if (av_frame_make_writable(e->frame) < 0) return -3;
    int n = e->frame->nb_samples, ch = e->channels;
    for (int c = 0; c < ch; c++) {
        float *plane = (float *)e->frame->data[c];
        for (int i = 0; i < n; i++) plane[i] = src[i * ch + c] / 32768.0f;
    }
    e->frame->pts = e->pts;
    e->pts += n;
    if (avcodec_send_frame(e->ctx, e->frame) < 0) return -4;
    int r = avcodec_receive_packet(e->ctx, e->pkt);
    if (r == AVERROR(EAGAIN)) return 0;
    if (r < 0) return -5;
    int size = e->pkt->size;
    if (size > cap) { av_packet_unref(e->pkt); return -6; }
    memcpy(dst, e->pkt->data, size);
    av_packet_unref(e->pkt);
    return size;
}

JNIEXPORT void JNICALL
Java_com_notdxbi_dxbi_tv_Ac3Native_close(JNIEnv *env, jclass cls, jlong handle) {
    enc_free((Enc *)(intptr_t)handle);
}
