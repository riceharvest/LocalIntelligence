package dev.localintelligence.core

/**
 * Marker for the pure-JVM core. See docs/architecture.md.
 *
 * If this module ever grows an `android.*` import, the architecture is broken:
 * the whole point of :core is that the agent loop, tool selection, loop detection,
 * context building and the entire eval suite run on a JVM in seconds, with no
 * emulator and no device.
 */
object LocalIntelligence
