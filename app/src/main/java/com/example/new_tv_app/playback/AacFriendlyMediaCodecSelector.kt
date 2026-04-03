package com.example.new_tv_app.playback

import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil

/**
 * Some IPTV VOD streams use AAC (often mp4a.40.2 / LATM, multi-channel) that the software
 * [c2.android.aac.decoder] rejects during raw config (log: aacDecoder_ConfigRaw decoderErr = 0x5),
 * while OMX or other vendor AAC decoders accept the same bitstream. This selector keeps the
 * default ordering for all codecs but moves [c2.android.aac.decoder] to the end for AAC.
 */
class AacFriendlyMediaCodecSelector(
    private val delegate: MediaCodecSelector = MediaCodecSelector.DEFAULT,
) : MediaCodecSelector {

    @Throws(MediaCodecUtil.DecoderQueryException::class)
    override fun getDecoderInfos(
        mimeType: String,
        requiresSecureDecoder: Boolean,
        requiresTunnelingDecoder: Boolean,
    ): List<MediaCodecInfo> {
        val infos =
            delegate.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
        if (infos.size <= 1) return infos
        if (mimeType != MimeTypes.AUDIO_AAC) return infos

        return infos.withIndex()
            .sortedWith(
                compareBy<IndexedValue<MediaCodecInfo>> { (_, info) ->
                    when {
                        info.name.contains("c2.android.aac", ignoreCase = true) -> 2
                        info.name.startsWith("OMX.", ignoreCase = true) -> 0
                        else -> 1
                    }
                }.thenBy { it.index },
            )
            .map { it.value }
    }
}
