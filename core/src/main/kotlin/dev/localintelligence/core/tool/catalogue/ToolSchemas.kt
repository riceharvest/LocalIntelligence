package dev.localintelligence.core.tool.catalogue

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The one JSON Schema per tool, for all 25 tools in [V0ToolCatalogue].
 *
 * ## Why this file is the single source, and which copy won
 *
 * Every tool's schema used to be written twice: once here in the catalogue and
 * once as a literal inside the `AgentTool` that actually ships in `:android`.
 * Measured on the compiled classes before this change, **all 25 differed** and
 * **none were equal**. The `:android` copy became canonical because it is the
 * only copy that ever reached anything:
 *
 * ```
 * AppContainer -> SimpleToolRegistry(androidTools(context))   // shipped tools
 *   -> AgentController.buildRequest(visible)
 *      -> GrammarBuilder.forActions(visible.map { it.definition })
 *      -> SystemPrompts / ContextBudget.renderTool(definition)
 *   -> ToolCallValidator.validate(... tool.definition.schema ...)   // argument check
 * ```
 *
 * `visible` is a subset of the *registry*, so the `:android` schema is what the
 * model is shown and what an incoming call is checked against. The catalogue's
 * copy was read by nothing except [CatalogueAgreement], which compared names,
 * tiers, origins and categories and never looked at it. A careful edit to a
 * catalogue schema could not have changed one thing the model saw.
 *
 * The drift was not cosmetic. It was, in both directions at once:
 *
 *  - **Argument names the tool does not read.** `web.fetch` catalogued
 *    `max_chars` and `notifications.list` catalogued `only_replyable`. Both tools
 *    read `maxChars` and `onlyReplyable`. The catalogue was teaching a caller the
 *    wrong spelling of an argument.
 *  - **Arguments that did not exist.** `calendar.create` catalogued `attendee`
 *    and `calendar.search` catalogued `date`; neither tool reads them, and
 *    `alarm.create` catalogued no `repeat` while the shipped schema has one.
 *  - **Bounds that disagreed with the code.** `device.vibrate` catalogued
 *    `minimum: 50`; the tool clamps to `VibrationLogic.MIN_DURATION_MS = 10` and
 *    its own schema said 10. `files.list` catalogued `maximum: 200` and
 *    "Default 25"; the tool's limit is 100 with a default of 20.
 *  - **Constraints only one side had.** `apps.open` carries `minProperties: 1`
 *    because one of `package`/`name` is genuinely required. The catalogue
 *    declared `required: []` and told the model an empty call was fine.
 *
 * ## What is NOT claimed here
 *
 * Unifying the two copies does not make the catalogue the authority on argument
 * *validity*; it makes the catalogue stop disagreeing with the code. A bound in
 * [ToolArgumentBounds] is still a number a human typed, and the tool's
 * `execute()` is still what enforces it. What changed is that there is now
 * exactly one place to change it, and [CatalogueAgreement] fails the build if a
 * tool stops using the schema declared here.
 */
object ToolSchemas {

    /** `files.list` — see [ToolSchemas]. */
    val filesList: JsonObject = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put("minimum", 1)
                        put("maximum", ToolArgumentBounds.FILES_MAX_LIMIT)
                        put("description", "How many documents to list. Default ${ToolArgumentBounds.FILES_DEFAULT_LIMIT}.")
                    })
                },
            )
            putJsonArray("required") { }
            put("additionalProperties", false)
        }

    /** `files.search` — see [ToolSchemas]. */
    val filesSearch: JsonObject = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put("query", buildJsonObject {
                        put("type", "string")
                        put("maxLength", ToolArgumentBounds.FILES_MAX_QUERY_CHARS)
                        put("description", "Case-insensitive substring of the file name.")
                    })
                    put("mime", buildJsonObject {
                        put("type", "string")
                        put("maxLength", ToolArgumentBounds.FILES_MAX_MIME_CHARS)
                        put("description", "Exact MIME type, e.g. application/pdf.")
                    })
                    put("modified_after", buildJsonObject {
                        put("type", "integer")
                        put("description", "Only files modified at or after this epoch-milliseconds value.")
                    })
                    put("modified_before", buildJsonObject {
                        put("type", "integer")
                        put("description", "Only files modified before this epoch-milliseconds value.")
                    })
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put("minimum", 1)
                        put("maximum", ToolArgumentBounds.FILES_MAX_LIMIT)
                        put("description", "How many matches to return. Default ${ToolArgumentBounds.FILES_DEFAULT_LIMIT}.")
                    })
                },
            )
            putJsonArray("required") { }
            put("additionalProperties", false)
        }

    /** `files.read_text` — see [ToolSchemas]. */
    val filesReadText: JsonObject = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put("uri", buildJsonObject {
                        put("type", "string")
                        put("description", "content:// URI from files.list or files.search.")
                    })
                },
            )
            putJsonArray("required") { add("uri") }
            put("additionalProperties", false)
        }

    /** `files.write_text` — see [ToolSchemas]. */
    val filesWriteText: JsonObject = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put("uri", buildJsonObject {
                        put("type", "string")
                        put("description", "content:// URI of the document to overwrite. Omit to create a new file.")
                    })
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("maxLength", ToolArgumentBounds.FILES_MAX_WRITE_NAME_CHARS)
                        put("description", "Filename for a new document, e.g. notes.txt.")
                    })
                    put("content", buildJsonObject {
                        put("type", "string")
                        put("description", "The text to write. At most ${ToolArgumentBounds.FILES_MAX_WRITE_CHARS} characters.")
                    })
                },
            )
            putJsonArray("required") { add("content") }
            put("additionalProperties", false)
        }

    /** `files.delete` — see [ToolSchemas]. */
    val filesDelete: JsonObject = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put("uri", buildJsonObject {
                        put("type", "string")
                        put("description", "content:// URI of the single document to delete.")
                    })
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("description", "Exact file name. Refused if it matches more than one document.")
                    })
                },
            )
            putJsonArray("required") { }
            put("additionalProperties", false)
        }

    /** `apps.list` — see [ToolSchemas]. */
    val appsList: JsonObject = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put("query", buildJsonObject {
                        put("type", "string")
                        put("maxLength", ToolArgumentBounds.APPS_MAX_QUERY_CHARS)
                        put("description", "Filter by app label or package name.")
                    })
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put("minimum", 1)
                        put("maximum", ToolArgumentBounds.APPS_MAX_LIMIT)
                        put("description", "How many apps to return. Default ${ToolArgumentBounds.APPS_DEFAULT_LIMIT}.")
                    })
                },
            )
            putJsonArray("required") { }
            put("additionalProperties", false)
        }

    /** `apps.open` — see [ToolSchemas]. */
    val appsOpen: JsonObject = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put("package", buildJsonObject {
                        put("type", "string")
                        put("description", "Exact package name, e.g. com.android.chrome. Preferred.")
                    })
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("maxLength", ToolArgumentBounds.APPS_MAX_QUERY_CHARS)
                        put("description", "App label to fuzzy-match, e.g. \"Maps\". Ambiguous names are refused.")
                    })
                },
            )
            putJsonArray("required") { }
            // Truthful: `required: []` alone told the model an empty call was
            // fine, and the tool then rejected it. One of the two arguments is
            // genuinely required, just not expressible as a named `required`
            // entry, so the standard constraint for that is minProperties.
            put("minProperties", 1)
            put("additionalProperties", false)
        }

    /** `apps.share` — see [ToolSchemas]. */
    val appsShare: JsonObject = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put("uri", buildJsonObject {
                        put("type", "string")
                        put("description", "content:// document URI from files.list or files.search.")
                    })
                    put("text", buildJsonObject {
                        put("type", "string")
                        put("maxLength", ToolArgumentBounds.APPS_MAX_TEXT_CHARS)
                        put("description", "Plain text to share, with no attachment.")
                    })
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("description", "Display name of the attachment, used for the MIME type and the share title.")
                    })
                    put("title", buildJsonObject {
                        put("type", "string")
                        put("maxLength", 200)
                        put("description", "Title for the share sheet.")
                    })
                },
            )
            putJsonArray("required") { }
            // One of 'uri' or 'text' is genuinely required; the tool rejects a
            // call with neither. Declared so the model is not invited to make
            // the call the tool will refuse.
            put("minProperties", 1)
            put("additionalProperties", false)
        }

    /** `clipboard.write` — see [ToolSchemas]. */
    val clipboardWrite: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("text") {
                    put("type", "string")
                    put("description", "The plain text to place on the clipboard.")
                    put("maxLength", ToolArgumentBounds.CLIPBOARD_MAX_WRITE_CHARS)
                }
                putJsonObject("label") {
                    put("type", "string")
                    put("description", "A short name for the clip, shown in the system clipboard UI.")
                }
            }
            putJsonArray("required") { add("text") }
        }

    /** `clipboard.read` — see [ToolSchemas]. */
    val clipboardRead: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("format") {
                    put("type", "string")
                    putJsonArray("enum") { add("text") }
                    put("description", "Only \"text\" is supported. Defaults to text.")
                }
            }
            putJsonArray("required") { }
        }

    /**
     * `device.battery` — see [ToolSchemas].
     *
     * `device.info` has an identical shape: both take no arguments. They are
     * kept as two declarations rather than one shared constant so that each
     * tool references the member that carries its own name, and so editing one
     * cannot silently retarget the other. The drift guard checks that
     * correspondence, which is how a `device.info` pointing at
     * `deviceBattery` was caught despite both schemas being equal.
     */
    val deviceBattery: JsonObject = buildJsonObject {
            put("type", "object")
            put("description", "No arguments.")
            putJsonObject("properties") { }
            putJsonArray("required") { }
        }

    /** `device.info` — see [ToolSchemas]. */
    val deviceInfo: JsonObject = buildJsonObject {
            put("type", "object")
            put("description", "No arguments.")
            putJsonObject("properties") { }
            putJsonArray("required") { }
        }

    /** `device.vibrate` — see [ToolSchemas]. */
    val deviceVibrate: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("duration_ms") {
                    put("type", "integer")
                    put("minimum", ToolArgumentBounds.VIBRATE_MIN_DURATION_MS)
                    put("maximum", ToolArgumentBounds.VIBRATE_MAX_DURATION_MS)
                    put(
                        "description",
                        "How long to buzz in milliseconds. Defaults to ${ToolArgumentBounds.VIBRATE_DEFAULT_DURATION_MS}.",
                    )
                }
            }
            putJsonArray("required") { }
        }

    /** `device.open_settings` — see [ToolSchemas]. */
    val deviceOpenSettings: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("screen") {
                    put("type", "string")
                    putJsonArray("enum") { SettingsScreen.ARG_NAMES.forEach { add(it) } }
                    put("description", "Which settings screen to open. One of: ${SettingsScreen.ARG_NAMES.joinToString(", ")}.")
                }
            }
            putJsonArray("required") { add("screen") }
        }

    /** `alarm.create` — see [ToolSchemas]. */
    val alarmCreate: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("hour") {
                    put("type", "integer")
                    put("minimum", ToolArgumentBounds.ALARM_MIN_HOUR)
                    put("maximum", ToolArgumentBounds.ALARM_MAX_HOUR)
                    put("description", "Hour in 24-hour time, 0-23.")
                }
                putJsonObject("minute") {
                    put("type", "integer")
                    put("minimum", ToolArgumentBounds.ALARM_MIN_MINUTE)
                    put("maximum", ToolArgumentBounds.ALARM_MAX_MINUTE)
                    put("description", "Minute, 0-59.")
                }
                putJsonObject("label") {
                    put("type", "string")
                    put("description", "Short description, e.g. \"take the bread out\".")
                }
                putJsonObject("id") {
                    put("type", "string")
                    put("description", "Stable id used later to cancel this exact alarm. Generated if omitted.")
                }
                putJsonObject("day_offset") {
                    put("type", "integer")
                    put("minimum", 0)
                    put("maximum", 7)
                    put(
                        "description",
                        "0 for today, 1 for tomorrow. When omitted, a time that has already passed " +
                            "today automatically rolls over to tomorrow.",
                    )
                }
                putJsonObject("repeat") {
                    put("type", "boolean")
                    put("description", "Repeating alarms are not supported. Leave this false or omit it.")
                }
            }
            putJsonArray("required") { add("hour") }
        }

    /** `alarm.list` — see [ToolSchemas]. */
    val alarmList: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") { }
            putJsonArray("required") { }
        }

    /** `alarm.cancel` — see [ToolSchemas]. */
    val alarmCancel: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("id") {
                    put("type", "string")
                    put("description", "The id of the ONE alarm to cancel. Takes precedence over hour/minute.")
                }
                putJsonObject("hour") {
                    put("type", "integer")
                    put("minimum", ToolArgumentBounds.ALARM_MIN_HOUR)
                    put("maximum", ToolArgumentBounds.ALARM_MAX_HOUR)
                    put("description", "Cancel the single alarm at this hour.")
                }
                putJsonObject("minute") {
                    put("type", "integer")
                    put("minimum", ToolArgumentBounds.ALARM_MIN_MINUTE)
                    put("maximum", ToolArgumentBounds.ALARM_MAX_MINUTE)
                    put("description", "Cancel the single alarm at this minute.")
                }
                putJsonObject("label") {
                    put("type", "string")
                    put("description", "Cancel the single alarm whose label contains this text.")
                }
            }
            putJsonArray("required") { }
        }

    /** `calendar.search` — see [ToolSchemas]. */
    val calendarSearch: JsonObject = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("from", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Start of the window: 2026-09-25, 2026-09-25T09:00:00, " +
                            "2026-09-25T09:00:00+02:00, epoch millis, or today/tomorrow/yesterday.",
                    )
                })
                put("to", buildJsonObject {
                    put("type", "string")
                    put("description", "End of the window, same formats as 'from'.")
                })
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional text matched against event title and location.")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("minimum", 1)
                    put("maximum", ToolArgumentBounds.CALENDAR_MAX_LIMIT)
                    put("default", ToolArgumentBounds.CALENDAR_DEFAULT_LIMIT)
                })
            })
            put("required", buildJsonArray { add("from"); add("to") })
        }

    /** `calendar.create` — see [ToolSchemas]. */
    val calendarCreate: JsonObject = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("title", buildJsonObject {
                    put("type", "string")
                    put("description", "Event title, 1-200 characters.")
                })
                put("start", buildJsonObject {
                    put("type", "string")
                    put("description", "Start as 2026-09-25T09:00:00 or 2026-09-25T09:00:00+02:00.")
                })
                put("durationMinutes", buildJsonObject {
                    put("type", "integer")
                    put("minimum", ToolArgumentBounds.CALENDAR_MIN_DURATION_MINUTES)
                    put("maximum", ToolArgumentBounds.CALENDAR_MAX_DURATION_MINUTES)
                    put("description", "Length in minutes (default 60, maximum 1440).")
                })
                put("end", buildJsonObject {
                    put("type", "string")
                    put("description", "Alternative to durationMinutes: the end time. Ignored if durationMinutes is given.")
                })
                put("location", buildJsonObject { put("type", "string") })
                put("description", buildJsonObject { put("type", "string") })
                put("allDay", buildJsonObject {
                    put("type", "boolean")
                    put("description", "True for a whole-day event; start is taken as the day it starts.")
                })
            })
            put("required", buildJsonArray { add("title"); add("start") })
        }

    /** `contacts.search` — see [ToolSchemas]. */
    val contactsSearch: JsonObject = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Name fragment or phone number to look for.")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("minimum", 1)
                    put("maximum", ToolArgumentBounds.CONTACTS_MAX_LIMIT)
                    put("default", ToolArgumentBounds.CONTACTS_DEFAULT_LIMIT)
                })
            })
            put("required", buildJsonArray { add("query") })
        }

    /** `contacts.get` — see [ToolSchemas]. */
    val contactsGet: JsonObject = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("id", buildJsonObject {
                    put("type", "integer")
                    put("description", "Contact id from contacts.search, for example 42.")
                })
            })
            put("required", buildJsonArray { add("id") })
        }

    /** `notifications.list` — see [ToolSchemas]. */
    val notificationsList: JsonObject = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put(
                        "limit",
                        buildJsonObject {
                            put("type", JsonPrimitive("integer"))
                            put(
                                "description",
                                JsonPrimitive("How many to return, ${ToolArgumentBounds.NOTIFICATIONS_DEFAULT_LIST_LIMIT} by default."),
                            )
                        },
                    )
                    put(
                        "query",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive("Optional text to match against app name, title or body."),
                            )
                        },
                    )
                    put(
                        "onlyReplyable",
                        buildJsonObject {
                            put("type", JsonPrimitive("boolean"))
                            put(
                                "description",
                                JsonPrimitive("Only notifications that accept a quick reply."),
                            )
                        },
                    )
                },
            )
            put("required", buildJsonArray { })
        }

    /** `notifications.reply` — see [ToolSchemas]. */
    val notificationsReply: JsonObject = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put(
                        "key",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive("Notification key from notifications.list, e.g. com.whatsapp#2."),
                            )
                        },
                    )
                    put(
                        "text",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("The message body to send."))
                        },
                    )
                },
            )
            put(
                "required",
                buildJsonArray {
                    add(JsonPrimitive("key"))
                    add(JsonPrimitive("text"))
                },
            )
        }

    /** `notifications.dismiss` — see [ToolSchemas]. */
    val notificationsDismiss: JsonObject = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put(
                        "key",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive("Notification key from notifications.list."),
                            )
                        },
                    )
                },
            )
            put("required", buildJsonArray { add(JsonPrimitive("key")) })
        }

    /** `web.fetch` — see [ToolSchemas]. */
    val webFetch: JsonObject = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put(
                        "url",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive("Absolute http:// or https:// address of the page."),
                            )
                        },
                    )
                    put(
                        "maxChars",
                        buildJsonObject {
                            put("type", JsonPrimitive("integer"))
                            put("minimum", JsonPrimitive(ToolArgumentBounds.WEB_MIN_MAX_CHARS))
                            put("maximum", JsonPrimitive(ToolArgumentBounds.WEB_MAX_MAX_CHARS))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Characters of text to return, ${ToolArgumentBounds.WEB_DEFAULT_MAX_CHARS} by default, " +
                                        "at most ${ToolArgumentBounds.WEB_MAX_MAX_CHARS}. The whole response is also capped " +
                                        "at the observation budget, so a larger value may not " +
                                        "return more text.",
                                ),
                            )
                        },
                    )
                    // Removed rather than documented around. This advertised a
                    // three-value enum and the tool never read it: `execute`
                    // pulls only "url" and "maxChars". An argument the model can
                    // send and that provably does nothing is a schema lying
                    // about its own arguments, and "html" in particular implies
                    // raw markup can be returned, which this tool will not do.
                    // HTML is always stripped to text — that is now stated in
                    // the tool description instead of being an argument.
                },
            )
            put("required", kotlinx.serialization.json.buildJsonArray { add(JsonPrimitive("url")) })
        }
}
