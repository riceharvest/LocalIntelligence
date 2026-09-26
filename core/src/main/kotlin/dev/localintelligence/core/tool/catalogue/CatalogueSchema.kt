package dev.localintelligence.core.tool.catalogue

/**
 * A note on schema DIALECTS, kept because the hazard was real and is now easier
 * to reintroduce than it looks.
 *
 * ## What this file used to be
 *
 * A single `objSchema(...)` builder that every one of the 25 catalogue schemas
 * was written through, so the catalogue could not drift into two shapes. That
 * guarantee was worth having and it was worth exactly nothing, because the
 * schemas it built were never read: [ToolSchemas] holds the copy the model
 * actually sees, and the tools build their definitions from that. The builder
 * is gone, and this file is what remains of the reasoning.
 *
 * ## The two dialects that must not come back
 *
 * **`required` as an object.** It is emitted as a JSON **array**, which is what
 * JSON Schema specifies and what the `required` field in `docs/tool-contract.md`
 * means. Some early tool implementations emitted it as an object
 * (`{ "content": "..." }`); that is a description map, not a constraint, and a
 * model reading it is told nothing about which arguments are mandatory. Every
 * reader of `required` here — [dev.localintelligence.core.tool.ToolCallValidator],
 * [dev.localintelligence.core.model.GrammarBuilder] and
 * [dev.localintelligence.core.policy.RiskPolicy]'s required-argument check —
 * parses it as an array, and a tool that emits an object gets silently treated
 * as declaring no required arguments at all.
 *
 * **`additionalProperties` present on some tools and not others.** It is set
 * where the schema author thought about it, and the 25 schemas in
 * [ToolSchemas] are a faithful record of that inconsistency rather than a
 * correction of it: measured on the compiled classes, **8** of the 25 carry
 * `additionalProperties: false` and **17** do not, because that is what
 * shipped. Unifying it would change what the model is shown on 17 tools, so it
 * is deliberately NOT done here.
 *
 * That flag is also not what keeps unknown arguments out. It is not read by any
 * validator in this codebase. [dev.localintelligence.core.tool.ToolCallValidator]
 * rejects an unrecognised top-level argument by checking it against the
 * `properties` keys, and the grammar simply will not generate a name that is
 * not in the schema. `additionalProperties: false` is the schema saying the
 * same thing to a grammar-constrained backend that does read it — belt, and the
 * braces are the actual belt.
 *
 * ## What to do when adding a tool
 *
 * Write the schema in [ToolSchemas] as a `val`, have the `:android` tool
 * reference it, and have the catalogue reference it. There is no third place to
 * update, and [CatalogueAgreement] now fails the build if the two sides of that
 * reference stop being the same object.
 */
internal object SchemaDialectNotes
