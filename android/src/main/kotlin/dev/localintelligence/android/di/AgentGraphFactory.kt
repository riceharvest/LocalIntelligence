package dev.localintelligence.android.di

import android.content.Context
import dev.localintelligence.android.data.LocalIntelligenceDatabase
import dev.localintelligence.android.data.memoryStore
import dev.localintelligence.android.data.sessionStore
import dev.localintelligence.android.inference.LlamaCppBackend
import dev.localintelligence.android.inference.ModelImporter
import dev.localintelligence.core.agent.AgentConfig
import dev.localintelligence.core.model.ModelBackend

/**
 * The one place `android.content.Context` enters the graph.
 *
 * Every other type in this package takes collaborators as constructor
 * parameters and touches no framework type, which is what keeps the wiring
 * testable on the JVM. This object is the seam: production calls
 * [create] with a real `Context`; tests construct [AgentGraph] directly and
 * never come here.
 *
 * ## Why `applicationContext`
 *
 * Everything downstream outlives whatever screen created it — the backend stays
 * resident between runs, and the database is process-scoped. Holding an Activity
 * `Context` here would keep a destroyed Activity's view hierarchy reachable for
 * as long as the process lives, which is the classic Activity leak. The
 * application context is the one that legitimately has the process lifetime.
 */
object AgentGraphFactory {

    /**
     * Builds the production graph.
     *
     * Construction is cheap by design: [AgentGraph] defers the database, the
     * stores and the backend, so this call opens no file and loads no model. A
     * cold start that creates the graph and shows the chat screen has cost a few
     * allocations.
     */
    fun create(
        context: Context,
        config: AgentConfig = AgentConfig(),
    ): AgentGraph {
        val appContext = context.applicationContext
        return AgentGraph(
            toolSource = AndroidToolSource(appContext),
            backendFactory = { llamaBackend(appContext) },
            memoryStoreFactory = { database(appContext).memoryStore() },
            sessionStoreFactory = { database(appContext).sessionStore() },
            config = config,
        )
    }

    /**
     * The importer is created here rather than passed in because
     * `ContentResolver` is the only thing it needs, and threading a
     * `ContentResolver` through the UI to hand it to an inference backend is
     * exactly the "reach for a global" this graph exists to avoid.
     */
    private fun llamaBackend(context: Context): ModelBackend =
        LlamaCppBackend(ModelImporter(context.contentResolver))

    /**
     * One database per process, shared by both stores.
     *
     * Two `Room.databaseBuilder` calls for the same file would give two
     * connection pools to one SQLite file, which Room warns about and which
     * wastes a file handle. `AgentGraph` holds this behind `by lazy`, so it is
     * built on first use rather than here.
     */
    private fun database(context: Context): LocalIntelligenceDatabase =
        LocalIntelligenceDatabase.build(context)
}
