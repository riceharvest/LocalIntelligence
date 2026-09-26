package dev.localintelligence.android.data

import android.content.Context
import dev.localintelligence.core.agent.SessionStore

/**
 * The durable [SessionStore], for the one caller in `:app` that needs it.
 *
 * ## Why this exists as a separate function
 *
 * `:android` declares Room as `implementation`, not `api`, so `RoomDatabase`
 * and everything reachable from it — including [LocalIntelligenceDatabase] and
 * its `sessionStore()` — are off `:app`'s compile classpath entirely. Verified
 * by compiling each form and reading
 * `Cannot access 'RoomDatabase' which is a supertype of 'LocalIntelligenceDatabase'`.
 *
 * So `:app` cannot name the type it wants. It reaches this through
 * `SessionStoreFactory.provide`, which resolves the function by name. That
 * indirection is a bridge, not an architecture: it exists so the two halves
 * could land independently, and it is the reason a missing function here
 * degrades to "the conversation lasts one process" rather than to a crash on
 * cold start.
 *
 * ## Why it is a function and not a global
 *
 * The database is built lazily, per context, and never at class-load time. A
 * cold start that goes straight to the chat must not open a database it will
 * not read — see the RAM budget in `docs/architecture.md` §16. This function is
 * the only thing that can cause the database to be constructed, and it is
 * reached only once, from `AppContainer`'s `by lazy` on the first run.
 */
fun durableSessionStore(context: Context): SessionStore =
    LocalIntelligenceDatabase.build(context.applicationContext).sessionStore()
