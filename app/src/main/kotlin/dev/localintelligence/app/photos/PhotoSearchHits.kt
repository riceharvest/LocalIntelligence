package dev.localintelligence.app.photos

/**
 * The photos the last successful `photos.search` in this process found, as URIs, so the chat can
 * show them as thumbnails under the answer.
 *
 * WHY A HOLDER AND NOT THE OBSERVATION: the model reads captions only, and a URI in its prompt
 * would be a string it can echo or invent. The tool writes the hits here after it has checked
 * them, and the transcript reads them once a run ends. The tool clears them on every search, so a
 * failed or empty search leaves nothing for the next answer to show.
 */
object PhotoSearchHits {
    /** Most recent search's photo URIs, best first. Written by the tool, read by the chat. */
    @Volatile
    var latest: List<String> = emptyList()
        internal set
}
