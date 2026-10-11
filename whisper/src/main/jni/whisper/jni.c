#include <jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>
#include "whisper.h"

#define UNUSED(x) (void)(x)
#define TAG "TahsinWhisperJNI"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

JNIEXPORT jlong JNICALL
Java_com_tahsin_whisper_WhisperLib_initContext(
        JNIEnv *env, jobject thiz, jstring model_path_str, jboolean use_gpu, jint alignment_preset) {
    UNUSED(thiz);
    const char *model_path_chars = (*env)->GetStringUTFChars(env, model_path_str, NULL);

    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = use_gpu;
    if (alignment_preset > WHISPER_AHEADS_NONE) {
        // Per-token times from cross-attention (DTW). Not supported together
        // with flash attention, so a context that needs word timings gives it up.
        cparams.flash_attn = false;
        cparams.dtw_token_timestamps = true;
        cparams.dtw_aheads_preset = (enum whisper_alignment_heads_preset) alignment_preset;
    }

    struct whisper_context *context =
            whisper_init_from_file_with_params(model_path_chars, cparams);
    (*env)->ReleaseStringUTFChars(env, model_path_str, model_path_chars);

    if (context == NULL) {
        LOGW("whisper_init_from_file_with_params returned NULL");
    }
    return (jlong) context;
}

JNIEXPORT void JNICALL
Java_com_tahsin_whisper_WhisperLib_freeContext(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    if (context != NULL) {
        whisper_free(context);
    }
}

JNIEXPORT jint JNICALL
Java_com_tahsin_whisper_WhisperLib_fullTranscribe(
        JNIEnv *env, jobject thiz,
        jlong context_ptr,
        jfloatArray audio_data,
        jint num_threads,
        jstring language_str,
        jstring initial_prompt_str,
        jboolean single_segment,
        jboolean no_context,
        jboolean token_timestamps,
        jint max_len,
        jboolean split_on_word,
        jint beam_size,
        jint best_of,
        jfloat temperature,
        jfloat temperature_inc,
        jfloat no_speech_thold,
        jfloat entropy_thold,
        jfloat logprob_thold,
        jboolean suppress_non_speech,
        jint audio_ctx,
        jint max_tokens) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    if (context == NULL) {
        return -1;
    }

    jfloat *audio_data_arr = (*env)->GetFloatArrayElements(env, audio_data, NULL);
    const jsize audio_data_length = (*env)->GetArrayLength(env, audio_data);

    const char *language = NULL;
    if (language_str != NULL) {
        language = (*env)->GetStringUTFChars(env, language_str, NULL);
    }

    const char *initial_prompt = NULL;
    if (initial_prompt_str != NULL) {
        initial_prompt = (*env)->GetStringUTFChars(env, initial_prompt_str, NULL);
    }

    // Beam search costs more compute but is markedly less prone to the
    // "repeat the previous phrase" failure mode that greedy decoding shows on
    // short recitation windows.
    const enum whisper_sampling_strategy strategy =
            beam_size > 1 ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY;

    struct whisper_full_params params = whisper_full_default_params(strategy);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.language = language != NULL ? language : "ar";
    params.detect_language = false;
    params.n_threads = num_threads;
    params.offset_ms = 0;
    params.no_context = no_context;
    params.single_segment = single_segment;
    params.token_timestamps = token_timestamps;
    params.max_len = max_len;
    params.split_on_word = split_on_word;
    params.temperature = temperature;
    params.temperature_inc = temperature_inc;
    params.no_speech_thold = no_speech_thold;
    params.entropy_thold = entropy_thold;
    params.logprob_thold = logprob_thold;
    params.suppress_blank = true;
    params.suppress_nst = suppress_non_speech;

    if (audio_ctx > 0) {
        params.audio_ctx = audio_ctx;
    }
    if (max_tokens > 0) {
        // Bounds a repetition loop, where the decoder emits the same phrase
        // until it hits the context limit — slow, and pure noise.
        params.max_tokens = max_tokens;
    }
    if (strategy == WHISPER_SAMPLING_BEAM_SEARCH) {
        params.beam_search.beam_size = beam_size;
    } else if (best_of > 0) {
        params.greedy.best_of = best_of;
    }
    if (initial_prompt != NULL && strlen(initial_prompt) > 0) {
        params.initial_prompt = initial_prompt;
    }

    whisper_reset_timings(context);
    const int result = whisper_full(context, params, audio_data_arr, audio_data_length);
    if (result != 0) {
        LOGW("whisper_full failed with %d", result);
    }

    if (language_str != NULL) {
        (*env)->ReleaseStringUTFChars(env, language_str, language);
    }
    if (initial_prompt_str != NULL && initial_prompt != NULL) {
        (*env)->ReleaseStringUTFChars(env, initial_prompt_str, initial_prompt);
    }
    (*env)->ReleaseFloatArrayElements(env, audio_data, audio_data_arr, JNI_ABORT);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_tahsin_whisper_WhisperLib_getSegmentCount(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    return context == NULL ? 0 : whisper_full_n_segments(context);
}

JNIEXPORT jstring JNICALL
Java_com_tahsin_whisper_WhisperLib_getSegmentText(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    const char *text = whisper_full_get_segment_text(context, index);
    return (*env)->NewStringUTF(env, text == NULL ? "" : text);
}

JNIEXPORT jlong JNICALL
Java_com_tahsin_whisper_WhisperLib_getSegmentStartMs(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(env);
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    // whisper reports centiseconds.
    return (jlong) whisper_full_get_segment_t0(context, index) * 10;
}

JNIEXPORT jlong JNICALL
Java_com_tahsin_whisper_WhisperLib_getSegmentEndMs(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(env);
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    return (jlong) whisper_full_get_segment_t1(context, index) * 10;
}

JNIEXPORT jfloat JNICALL
Java_com_tahsin_whisper_WhisperLib_getSegmentNoSpeechProb(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(env);
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    return whisper_full_get_segment_no_speech_prob(context, index);
}

// Mean token probability for a segment. Used as the acoustic confidence that
// gates whether a disagreement is reported to the reciter as a mistake.
JNIEXPORT jfloat JNICALL
Java_com_tahsin_whisper_WhisperLib_getSegmentAvgTokenProb(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(env);
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    const int n_tokens = whisper_full_n_tokens(context, index);
    if (n_tokens <= 0) {
        return 0.0f;
    }
    float sum = 0.0f;
    int counted = 0;
    for (int i = 0; i < n_tokens; i++) {
        const whisper_token id = whisper_full_get_token_id(context, index, i);
        if (id >= whisper_token_eot(context)) {
            continue; // skip special/timestamp tokens
        }
        sum += whisper_full_get_token_p(context, index, i);
        counted++;
    }
    return counted == 0 ? 0.0f : sum / (float) counted;
}

JNIEXPORT jint JNICALL
Java_com_tahsin_whisper_WhisperLib_getSegmentTokenCount(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(env);
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    return whisper_full_n_tokens(context, index);
}

JNIEXPORT jstring JNICALL
Java_com_tahsin_whisper_WhisperLib_getTokenText(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint segment, jint token) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    const char *text = whisper_full_get_token_text(context, segment, token);
    return (*env)->NewStringUTF(env, text == NULL ? "" : text);
}

JNIEXPORT jfloat JNICALL
Java_com_tahsin_whisper_WhisperLib_getTokenProb(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint segment, jint token) {
    UNUSED(env);
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    return whisper_full_get_token_p(context, segment, token);
}

JNIEXPORT jlong JNICALL
Java_com_tahsin_whisper_WhisperLib_getTokenStartMs(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint segment, jint token) {
    UNUSED(env);
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    whisper_token_data data = whisper_full_get_token_data(context, segment, token);
    return (jlong) data.t0 * 10;
}

// DTW start time of a text token, or -1 for special/timestamp tokens and for a
// context created without an alignment preset.
JNIEXPORT jlong JNICALL
Java_com_tahsin_whisper_WhisperLib_getTokenDtwMs(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint segment, jint token) {
    UNUSED(env);
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    if (whisper_full_get_token_id(context, segment, token) >= whisper_token_eot(context)) {
        return -1;
    }
    whisper_token_data data = whisper_full_get_token_data(context, segment, token);
    return data.t_dtw < 0 ? -1 : (jlong) data.t_dtw * 10;
}

// Raw token bytes. A BPE token can end in the middle of a multi-byte UTF-8
// character, which NewStringUTF must never be given.
JNIEXPORT jbyteArray JNICALL
Java_com_tahsin_whisper_WhisperLib_getTokenBytes(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint segment, jint token) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    const char *text = whisper_full_get_token_text(context, segment, token);
    const jsize length = text == NULL ? 0 : (jsize) strlen(text);
    jbyteArray out = (*env)->NewByteArray(env, length);
    if (out != NULL && length > 0) {
        (*env)->SetByteArrayRegion(env, out, 0, length, (const jbyte *) text);
    }
    return out;
}

JNIEXPORT jint JNICALL
Java_com_tahsin_whisper_WhisperLib_getTextLayerCount(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    return context == NULL ? 0 : whisper_model_n_text_layer(context);
}

JNIEXPORT jboolean JNICALL
Java_com_tahsin_whisper_WhisperLib_isMultilingual(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    return context != NULL && whisper_is_multilingual(context);
}

JNIEXPORT jstring JNICALL
Java_com_tahsin_whisper_WhisperLib_getSystemInfo(JNIEnv *env, jobject thiz) {
    UNUSED(thiz);
    const char *sysinfo = whisper_print_system_info();
    return (*env)->NewStringUTF(env, sysinfo == NULL ? "" : sysinfo);
}
