package dev.localintelligence.app.settings

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One measured result: how a model did on one device class in the eval suite.
 *
 * WHY the rows are bundled and not fetched: Simple mode must pick a model offline, and a
 * pick nobody can reproduce from the repository is the failure docs/measurements.md warns
 * about. The file is written only by scripts/run-evals.py and docs/scores/build_scores.py
 * from result files, never by hand.
 *
 * [repo] and [file] must equal one of the models Simple mode may offer, or the row is ignored.
 * [deviceClass] is [SimpleSetup.deviceClass]'s string. [passRate] is in 0..1.
 */
@Serializable
data class ScoreRow(
    val repo: String,
    val file: String,
    val deviceClass: String,
    val passRate: Double,
    val decodeTokPerS: Double? = null,
    val runs: Int = 0,
)

/**
 * The bundled scores file, `assets/scores.json`. Empty today, so Simple mode keeps its table.
 *
 * WHY a missing or unreadable file reads as empty and never as an error: the table is always
 * the fallback, and a broken asset must not stop the app from choosing a model at all.
 */
@Serializable
data class BundledScores(
    val schemaVersion: Int = 1,
    val rows: List<ScoreRow> = emptyList(),
) {
    companion object {
        const val ASSET = "scores.json"

        /** The schema this reader understands. A higher version is read as empty, not guessed at. */
        const val SUPPORTED_SCHEMA = 1

        val EMPTY = BundledScores()

        fun load(context: Context): BundledScores = try {
            val text = context.assets.open(ASSET).bufferedReader().use { it.readText() }
            val parsed = Json { ignoreUnknownKeys = true }.decodeFromString(BundledScores.serializer(), text)
            if (parsed.schemaVersion == SUPPORTED_SCHEMA) parsed else EMPTY
        } catch (_: Exception) {
            EMPTY
        }
    }
}
