package dev.localintelligence.core.media

/**
 * The shape of the audio a transcription hands the model, in one place.
 *
 * WHY 30 SECONDS: llama.cpp's LFM3 audio path (`tools/mtmd/mtmd.cpp`, the note above
 * `mtmd_audio_stream_lfm3a`) says the model's training samples are at most 30 s, and
 * it restarts its own sequence after 15 s. A chunk at the limit is the longest clip the
 * model was trained to read. Chunks are cut at exact sample counts with no overlap, so a
 * word that straddles a boundary can be lost or heard twice.
 */
object AudioChunks {
    /** The rate the model reads. Recordings are resampled to it. */
    const val SAMPLE_RATE: Int = 16_000

    const val CHUNK_SECONDS: Int = 30

    /** Samples in one full chunk: the last chunk of a file may be shorter. */
    const val CHUNK_SAMPLES: Int = SAMPLE_RATE * CHUNK_SECONDS
}
