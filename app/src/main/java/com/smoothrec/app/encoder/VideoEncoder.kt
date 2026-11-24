package com.smoothrec.app.encoder

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.nio.ByteBuffer

/** Salida de los codificadores: el muxer se suscribe a estos eventos con un id de pista. */
interface EncoderOutput {
    fun onOutputFormatChanged(trackId: String, format: MediaFormat)
    fun onEncodedData(trackId: String, byteBuffer: ByteBuffer, info: MediaCodec.BufferInfo)

    companion object {
        const val TRACK_VIDEO = "video"
        const val TRACK_AUDIO = "audio"
    }
}

/** Contrato del encoder de vídeo implementado en Fase 1. */
interface VideoEncoder {
    fun start(config: EncoderConfig, output: EncoderOutput)
    fun inputSurface(): Surface
    /** Señala el fin del stream sin esperar, para drenar audio y vídeo a la vez. */
    fun signalEndOfStream()
    /** Espera el drenado de los buffers pendientes. */
    fun stop()
    fun release()
}
