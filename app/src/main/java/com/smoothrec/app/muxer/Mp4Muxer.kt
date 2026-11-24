package com.smoothrec.app.muxer

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.ParcelFileDescriptor
import android.util.Log
import com.smoothrec.app.audio.CaptureDiagnostics
import com.smoothrec.app.encoder.EncoderOutput
import java.nio.ByteBuffer

/**
 * Escribe vídeo H.264 y, cuando está disponible, audio AAC en un MP4 sobre el fd
 * de MediaStore. Las pistas se registran con su formato real y el muxer arranca
 * cuando están todas listas, de modo que no se pierden muestras iniciales.
 */
class Mp4Muxer(private val fileDescriptor: ParcelFileDescriptor) : EncoderOutput {

    private data class PendingSample(
        val trackId: String,
        val data: ByteBuffer,
        val info: MediaCodec.BufferInfo,
    )

    private val lock = Any()
    private val formats = LinkedHashMap<String, MediaFormat>()
    private val indices = HashMap<String, Int>()
    private val pending = ArrayList<PendingSample>()

    /**
     * MediaCodec con entrada Surface entrega PTS del reloj monotónico del
     * sistema, igual que el audio anclado con AudioRecord.getTimestamp().
     * Se usa un único origen para ambas pistas: rebasear cada una por su
     * primera muestra eliminaba el retardo real entre vídeo y audio y hacía
     * que el audio sonara adelantado.
     */
    private var commonOriginUs = 0L
    private val firstRawPts = HashMap<String, Long>()
    private val lastPts = HashMap<String, Long>()
    private var monotonicFixes = 0

    private var expectedTracks = 1
    private var muxer: MediaMuxer? = null
    private var started = false
    private var released = false
    private var bytesWritten = 0L
    private var writeErrors = 0
    private var stopFailed = false
    private var firstVideoSampleNs = 0L

    /** Debe llamarse antes de iniciar los codificadores. */
    fun prepare(expectedTracks: Int, sessionStartNs: Long = System.nanoTime()) {
        synchronized(lock) {
            this.expectedTracks = expectedTracks.coerceAtLeast(1)
            commonOriginUs = sessionStartNs / 1000
        }
        Log.i(TAG, "Origen temporal común: $commonOriginUs us")
        CaptureDiagnostics.log("Muxer: origen temporal común $commonOriginUs us")
    }

    private fun ensureMuxer() {
        if (muxer == null) {
            muxer = MediaMuxer(
                fileDescriptor.fileDescriptor,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
            )
        }
    }

    @Synchronized
    override fun onOutputFormatChanged(trackId: String, format: MediaFormat) {
        if (released || formats.containsKey(trackId)) return
        if (started && !indices.containsKey(trackId)) {
            // MediaMuxer no admite añadir pistas después de start(): el formato
            // llegó tarde (por ejemplo, un encoder opcional que falló).
            Log.w(TAG, "Formato tardío de $trackId: la pista no se añadirá")
            return
        }
        formats[trackId] = format
        if (trackId == EncoderOutput.TRACK_AUDIO) {
            // La pista AAC se registra con la primera muestra real, no solo con
            // el formato. Si las fuentes de audio fallan, el MP4 no queda con
            // una pista de audio vacía.
            return
        }
        try {
            ensureMuxer()
            indices[trackId] = muxer!!.addTrack(format)
            maybeStart()
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo registrar la pista $trackId", e)
        }
    }

    /** Arranca el muxer solo cuando todas las pistas esperadas están registradas. */
    private fun maybeStart() {
        if (started || muxer == null) return
        if (indices.size < expectedTracks) return
        try {
            muxer!!.start()
            started = true
            flushPending()
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo iniciar el muxer", e)
        }
    }

    @Synchronized
    override fun onEncodedData(trackId: String, byteBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (released || info.size <= 0) return
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return

        // Red de seguridad: si el audio nunca entrega su primera muestra (por
        // ejemplo, un fallo del codec), el vídeo no debe quedarse retenido en
        // memoria hasta el final. Tras 2 s se publica el MP4 solo con vídeo.
        if (!started && expectedTracks > 1 && trackId == EncoderOutput.TRACK_VIDEO) {
            val now = System.nanoTime()
            if (firstVideoSampleNs == 0L) {
                firstVideoSampleNs = now
            } else if (now - firstVideoSampleNs > AUDIO_WAIT_TIMEOUT_NS) {
                Log.w(TAG, "Sin muestras de audio tras 2 s: se inicia el MP4 solo con vídeo")
                startWithAvailableTracks()
            }
        }

        var trackIndex = indices[trackId]
        if (trackIndex == null) {
            val format = formats[trackId]
            if (!started && format != null) {
                try {
                    ensureMuxer()
                    trackIndex = muxer!!.addTrack(format)
                    indices[trackId] = trackIndex
                    maybeStart()
                } catch (e: Exception) {
                    Log.e(TAG, "No se pudo registrar la pista $trackId con su primera muestra", e)
                    return
                }
            } else {
                // El formato llegó después de start(): MediaMuxer ya no admite
                // la pista. No acumulamos muestras que nunca se podrán escribir.
                Log.w(TAG, "Muestra de $trackId descartada: pista no registrada antes de start()")
                return
            }
        }
        val index = trackIndex ?: return

        val copy = ByteBuffer.allocateDirect(info.size).apply {
            put(byteBuffer.duplicate().apply {
                position(info.offset)
                limit(info.offset + info.size)
            })
            flip()
        }
        val copyInfo = MediaCodec.BufferInfo().apply {
            set(0, copy.limit(), normalizePts(trackId, info.presentationTimeUs), info.flags)
        }

        if (!started) {
            pending.add(PendingSample(trackId, copy, copyInfo))
            return
        }
        write(index, copy, copyInfo)
    }

    /**
     * Convierte el PTS del codificador a microsegundos desde el inicio común de
     * la sesión. No se rebasea por pista: eso borraba el desfase real entre el
     * inicio del vídeo y el del audio. Se aplica también a las muestras que
     * estaban pendientes de que arrancara el muxer.
     */
    private fun normalizePts(trackId: String, raw: Long): Long {
        if (!firstRawPts.containsKey(trackId)) {
            firstRawPts[trackId] = raw
            Log.i(TAG, "PTS inicial $trackId: $raw us (origen=$commonOriginUs)")
            CaptureDiagnostics.log("Muxer: PTS inicial $trackId=$raw us, origen=$commonOriginUs us")
        }
        var pts = raw - commonOriginUs
        if (pts < 0) pts = 0
        val previous = lastPts[trackId]
        if (previous != null && pts <= previous) {
            monotonicFixes++
            Log.w(TAG, "PTS no monótono en $trackId: $pts <= $previous (raw=$raw)")
            pts = previous + 1
        }
        lastPts[trackId] = pts
        return pts
    }

    /** Arranca el muxer aunque no llegue el formato de alguna pista opcional. */
    @Synchronized
    fun startWithAvailableTracks() {
        if (released || started) return
        expectedTracks = 1
        if (indices.isNotEmpty()) {
            ensureMuxer()
            maybeStart()
        }
    }

    private fun write(index: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        try {
            muxer?.writeSampleData(index, buffer, info)
            bytesWritten += info.size
        } catch (e: Exception) {
            writeErrors++
            Log.w(TAG, "Error al escribir una muestra ($writeErrors)", e)
        }
    }

    private fun flushPending() {
        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            val sample = iterator.next()
            val index = indices[sample.trackId]
            if (index != null) write(index, sample.data, sample.info)
            iterator.remove()
        }
    }

    fun hasData(): Boolean = bytesWritten > 0

    fun hasTrack(trackId: String): Boolean = synchronized(lock) { indices.containsKey(trackId) }

    /** True solo si todas las muestras se escribieron y el contenedor se cerró bien. */
    fun finalizedOk(): Boolean = released && !stopFailed && writeErrors == 0

    @Synchronized
    fun stop() {
        if (released) return
        try {
            if (started) muxer?.stop()
        } catch (e: IllegalStateException) {
            stopFailed = true
            Log.w(TAG, "El muxer no se pudo detener limpiamente", e)
        }
        try {
            muxer?.release()
        } catch (e: Exception) {
            stopFailed = true
            Log.w(TAG, "Error al liberar el muxer", e)
        }
        released = true
        muxer = null
        started = false
        val summary = "Muxer: PTS finales ${lastPts.entries.joinToString { "${it.key}=${it.value}" }} " +
            "us, correcciones de monotonía=$monotonicFixes"
        Log.i(TAG, summary)
        CaptureDiagnostics.log(summary)
        indices.clear()
        formats.clear()
        firstRawPts.clear()
        lastPts.clear()
        pending.clear()
        monotonicFixes = 0
    }

    private companion object {
        const val TAG = "Mp4Muxer"
        const val AUDIO_WAIT_TIMEOUT_NS = 2_000_000_000L
    }
}
