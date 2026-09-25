package dev.localintelligence.core.execution

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Test doubles for the execution package.
 *
 * Every one of these exists to make a timing claim testable *without wall-clock
 * sleeping*. The rules this package implements are all about what happens when
 * something is slow, so the naive test suite waits 30 seconds to prove a 30
 * second deadline works. That suite gets skipped, then disabled, then ignored.
 * Here, "slow" is a latch that a test opens on its own schedule.
 *
 * Nothing in this file sleeps. Where a real thread is unavoidable — and for an
 * uninterruptible block it is, because you cannot interrupt a fake — the thread
 * is a daemon parked on a latch, so a forgotten release cannot wedge the JVM.
 */
object Fixtures {

    /**
     * A latch a test opens by hand, standing in for "time passing".
     *
     * Releasing is idempotent so a test can release in an `after` block even if
     * the code under test already finished.
     */
    class Gate {
        private val latch = CountDownLatch(1)
        private val hits = AtomicInteger(0)

        /** True once a waiter has arrived. Lets a test synchronise without sleeping. */
        val reached: Boolean get() = hits.get() > 0

        /** How long each wait parks before re-checking the latch. */
        private val POLL_MS = 20L

        fun arrive() {
            hits.incrementAndGet()
            // Deliberately uninterruptible: the catch RETRIES the wait rather
            // than returning, because a socket read on Android ignores the
            // interrupt flag exactly like this. An earlier version returned from
            // the catch, which made this fixture interruptible and silently
            // turned the hard-case test into the easy one.
            var released = false
            while (!released) {
                try {
                    latch.await(POLL_MS, TimeUnit.MILLISECONDS)
                    released = latch.count == 0L
                } catch (ignored: InterruptedException) {
                    // Swallow and keep waiting. That is the whole point.
                    Thread.interrupted()
                }
            }
        }

        /** Lets the blocked work return. Safe to call repeatedly. */
        fun release() = latch.countDown()
    }

    /**
     * A block that ignores interrupts and only returns when [Gate.release] is
     * called. This is the socket-read case, simulated exactly.
     */
    fun uninterruptible(gate: Gate): () -> String = { gate.arrive(); "finished anyway" }

    /** A block that returns immediately. */
    fun instant(value: String): () -> String = { value }

    /** A block that throws. */
    fun boom(message: String = "kaboom"): () -> String = { throw IllegalStateException(message) }

    /**
     * A block that honours interrupts the way well-written I/O does: it throws
     * `InterruptedException` promptly. The contrast with [uninterruptible] is the
     * point — both are legal, and the runtime must report them differently.
     */
    fun interruptible(): () -> String = {
        try {
            Thread.sleep(60_000)
            "finished anyway"
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        }
    }

    /**
     * Starts [threadCount] threads that all call [body] once [start] is opened.
     *
     * Returns a handle that blocks until they have all finished, so a
     * concurrency test never has to sleep and ask "did it probably finish by
     * now?".
     */
    class ThreadStorm(private val threadCount: Int) {
        private val start = CountDownLatch(1)
        private val done = CountDownLatch(threadCount)

        /**
         * Counts how many threads observed a `true` return from a one-shot CAS
         * (`CancellationToken.cancel`). Exactly one must, ever: more than one
         * means the token transitioned twice and its listeners can run twice.
         */
        val winners: AtomicInteger = AtomicInteger(0)

        fun run(body: (index: Int) -> Unit) {
            repeat(threadCount) { index ->
                Thread {
                    try {
                        start.await()
                        body(index)
                    } catch (ignored: InterruptedException) {
                        Thread.currentThread().interrupt()
                    } finally {
                        done.countDown()
                    }
                }.apply { isDaemon = true }.start()
            }
        }

        /** Opens the gate. Every thread leaves `run` at the same moment. */
        fun release() = start.countDown()

        /**
         * Waits for every thread. Returns false on timeout rather than throwing,
         * so a failing test reports "these threads never finished" instead of an
         * opaque interrupt.
         */
        fun awaitDone(timeoutMs: Long = 10_000): Boolean = done.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    /** Marks a flag, for asserting a block was entered exactly once. */
    fun counter(): AtomicBoolean = AtomicBoolean(false)
}
