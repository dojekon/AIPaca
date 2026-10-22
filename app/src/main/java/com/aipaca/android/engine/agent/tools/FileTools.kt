package com.aipaca.android.engine.agent.tools

import com.aipaca.android.engine.agent.AgentTool
import com.aipaca.android.engine.agent.AgentToolStatus
import com.aipaca.android.engine.agent.ToolResult
import java.nio.file.FileSystems
import java.nio.file.Paths
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

private const val LIST_LIMIT = 200
private const val FIND_LIMIT = 200
private const val GREP_DEFAULT_RESULTS = 50
private const val READ_DEFAULT_LIMIT = 200
private const val READ_MAX_LIMIT = 2000
private const val READ_MAX_CHARS = 256 * 1024
private const val GREP_MAX_FILE_BYTES = 512L * 1024L

private val toolJson = Json { ignoreUnknownKeys = true }

private val TEXT_EXTENSIONS = setOf(
    "txt", "md", "json", "jsonl", "csv", "tsv", "xml", "yaml", "yml", "html", "css",
    "js", "ts", "py", "kt", "java", "c", "h", "cpp", "hpp", "rs", "go", "sh", "toml",
    "ini", "log", "properties",
)

internal fun errorResult(message: String?): ToolResult =
    ToolResult(AgentToolStatus.ERROR, message ?: "Tool failed")

internal fun readParams(paramsJson: String): Result<JsonObject> =
    try {
        val element = toolJson.parseToJsonElement(paramsJson.ifBlank { "{}" })
        if (element is JsonObject) {
            Result.success(element)
        } else {
            Result.failure(IllegalArgumentException("Arguments must be a JSON object"))
        }
    } catch (e: Exception) {
        Result.failure(IllegalArgumentException("Invalid arguments JSON: ${e.message}"))
    }

internal fun JsonObject.requiredString(key: String): Result<String> {
    val element = this[key]
        ?: return Result.failure(IllegalArgumentException("Missing required parameter: $key"))
    val primitive = element as? JsonPrimitive
    if (primitive == null || primitive is JsonNull || !primitive.isString) {
        return Result.failure(IllegalArgumentException("Parameter $key must be a string"))
    }
    return Result.success(primitive.content)
}

internal fun JsonObject.optionalString(key: String): Result<String?> {
    val element = this[key]
    if (element == null || element is JsonNull) return Result.success(null)
    val primitive = element as? JsonPrimitive
    if (primitive == null || !primitive.isString) {
        return Result.failure(IllegalArgumentException("Parameter $key must be a string"))
    }
    return Result.success(primitive.content)
}

internal fun JsonObject.optionalInt(key: String): Result<Int?> {
    val element = this[key]
    if (element == null || element is JsonNull) return Result.success(null)
    val primitive = element as? JsonPrimitive
    val value = primitive?.intOrNull
        ?: return Result.failure(IllegalArgumentException("Parameter $key must be an integer"))
    return Result.success(value)
}

class ListTool(private val workspace: AgentWorkspace) : AgentTool {
    override val name: String = "list"

    override val description: String = "List files and directories in a sandbox directory."

    override val parametersJsonSchema: String = LIST_SCHEMA

    override suspend fun execute(paramsJson: String): ToolResult {
        return try {
            val params = readParams(paramsJson).getOrElse { return errorResult(it.message) }
            val path = params.optionalString("path").getOrElse { return errorResult(it.message) } ?: "."
            val entries = workspace.list(path).getOrElse { return errorResult(it.message) }

            val visible = entries.take(LIST_LIMIT)
            val output = visible.joinToString("\n") { entry ->
                if (entry.isDirectory) "${entry.name}/" else "${entry.name} ${entry.sizeBytes}"
            }
            val truncated = entries.size > LIST_LIMIT
            ToolResult(
                status = AgentToolStatus.OK,
                output = output,
                truncated = truncated,
                meta = if (truncated) mapOf("truncated" to "true") else emptyMap(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorResult(e.message)
        }
    }
}

class FindTool(private val workspace: AgentWorkspace) : AgentTool {
    override val name: String = "find"

    override val description: String = "Find files and directories whose paths match a glob pattern."

    override val parametersJsonSchema: String = FIND_SCHEMA

    override suspend fun execute(paramsJson: String): ToolResult {
        return try {
            val params = readParams(paramsJson).getOrElse { return errorResult(it.message) }
            val pattern = params.requiredString("pattern").getOrElse { return errorResult(it.message) }
            val path = params.optionalString("path").getOrElse { return errorResult(it.message) } ?: "."
            val base = workspace.validate(path).getOrElse { return errorResult(it.message) }

            val glob = if ('/' in pattern) pattern else "**/$pattern"
            val matcher = FileSystems.getDefault().getPathMatcher("glob:$glob")
            val all = workspace.walk(base).getOrElse { return errorResult(it.message) }
                .map { it.path }
                .filter { matcher.matches(Paths.get(it)) }
                .sorted()

            val visible = all.take(FIND_LIMIT)
            val truncated = all.size > FIND_LIMIT
            ToolResult(
                status = AgentToolStatus.OK,
                output = visible.joinToString("\n"),
                truncated = truncated,
                meta = if (truncated) mapOf("truncated" to "true") else emptyMap(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorResult(e.message)
        }
    }
}

class GrepTool(private val workspace: AgentWorkspace) : AgentTool {
    override val name: String = "grep"

    override val description: String = "Search text files for a regular expression or literal string."

    override val parametersJsonSchema: String = GREP_SCHEMA

    override suspend fun execute(paramsJson: String): ToolResult {
        return try {
            val params = readParams(paramsJson).getOrElse { return errorResult(it.message) }
            val pattern = params.requiredString("pattern").getOrElse { return errorResult(it.message) }
            val path = params.optionalString("path").getOrElse { return errorResult(it.message) } ?: "."
            val maxResults = (params.optionalInt("maxResults").getOrElse { return errorResult(it.message) }
                ?: GREP_DEFAULT_RESULTS).coerceIn(1, 200)
            val base = workspace.validate(path).getOrElse { return errorResult(it.message) }
            val baseEntry = workspace.stat(base).getOrElse { return errorResult(it.message) }
                ?: return errorResult("Path not found: $path")

            val regex = try {
                Regex(pattern)
            } catch (e: Exception) {
                null
            }

            val files = if (!baseEntry.isDirectory) {
                listOf(baseEntry)
            } else {
                workspace.walk(base).getOrElse { return errorResult(it.message) }
                    .filter { !it.isDirectory }
                    .filter { isTextCandidate(it.name) }
                    .filter { it.sizeBytes <= GREP_MAX_FILE_BYTES }
                    .sortedBy { it.path }
            }

            val results = mutableListOf<String>()
            var truncated = false
            matches@ for (file in files) {
                if (file.sizeBytes > GREP_MAX_FILE_BYTES) continue
                if (!isTextCandidate(file.name)) continue
                val content = workspace.readText(file.path).getOrElse { continue }
                if (content.contains('\u0000')) continue

                var lineNumber = 0
                for (line in content.lineSequence()) {
                    lineNumber++
                    val matches = if (regex != null) regex.containsMatchIn(line) else line.contains(pattern)
                    if (!matches) continue
                    if (results.size >= maxResults) {
                        truncated = true
                        break@matches
                    }
                    results += "${file.path}:$lineNumber: $line"
                }
            }

            ToolResult(
                status = AgentToolStatus.OK,
                output = results.joinToString("\n"),
                truncated = truncated,
                meta = if (truncated) mapOf("truncated" to "true") else emptyMap(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorResult(e.message)
        }
    }
}

class ReadTool(private val workspace: AgentWorkspace) : AgentTool {
    override val name: String = "read"

    override val description: String = "Read a UTF-8 text file with numbered lines."

    override val parametersJsonSchema: String = READ_SCHEMA

    override suspend fun execute(paramsJson: String): ToolResult {
        return try {
            val params = readParams(paramsJson).getOrElse { return errorResult(it.message) }
            val path = params.requiredString("path").getOrElse { return errorResult(it.message) }
            val offset = params.optionalInt("offset").getOrElse { return errorResult(it.message) } ?: 1
            val limit = (params.optionalInt("limit").getOrElse { return errorResult(it.message) }
                ?: READ_DEFAULT_LIMIT).coerceIn(1, READ_MAX_LIMIT)
            if (offset < 1) return errorResult("offset must be >= 1")

            val relative = workspace.validate(path).getOrElse { return errorResult(it.message) }
            val entry = workspace.stat(relative).getOrElse { return errorResult(it.message) }
            if (entry == null || entry.isDirectory) return errorResult("Not a file: $path")

            val text = workspace.readText(relative).getOrElse { return errorResult(it.message) }

            val builder = StringBuilder()
            var truncated = false
            var lineNumber = offset
            var emitted = 0
            val lines = text.lineSequence().drop(offset - 1).iterator()
            while (lines.hasNext() && emitted < limit) {
                val formatted = String.format("%6d| %s", lineNumber, lines.next())
                val needed = formatted.length + if (builder.isEmpty()) 0 else 1
                if (builder.length + needed > READ_MAX_CHARS) {
                    if (builder.isNotEmpty()) builder.append('\n')
                    builder.append("[truncated: output limit reached]")
                    truncated = true
                    break
                }
                if (builder.isNotEmpty()) builder.append('\n')
                builder.append(formatted)
                lineNumber++
                emitted++
            }

            ToolResult(
                status = AgentToolStatus.OK,
                output = builder.toString(),
                truncated = truncated,
                meta = if (truncated) mapOf("truncated" to "true") else emptyMap(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorResult(e.message)
        }
    }
}

class WriteTool(private val workspace: AgentWorkspace) : AgentTool {
    override val name: String = "write"

    override val description: String = "Write UTF-8 text to a file, creating parent directories."

    override val parametersJsonSchema: String = WRITE_SCHEMA

    override suspend fun execute(paramsJson: String): ToolResult {
        return try {
            val params = readParams(paramsJson).getOrElse { return errorResult(it.message) }
            val path = params.requiredString("path").getOrElse { return errorResult(it.message) }
            val content = params.requiredString("content").getOrElse { return errorResult(it.message) }
            val relative = workspace.validate(path).getOrElse { return errorResult(it.message) }

            workspace.writeText(relative, content).getOrElse { return errorResult(it.message) }

            val bytes = content.toByteArray(Charsets.UTF_8)
            ToolResult(AgentToolStatus.OK, "Wrote ${bytes.size} bytes to $relative")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorResult(e.message)
        }
    }
}

class EditTool(private val workspace: AgentWorkspace) : AgentTool {
    override val name: String = "edit"

    override val description: String = "Replace one exact occurrence of a string in a file."

    override val parametersJsonSchema: String = EDIT_SCHEMA

    override suspend fun execute(paramsJson: String): ToolResult {
        return try {
            val params = readParams(paramsJson).getOrElse { return errorResult(it.message) }
            val path = params.requiredString("path").getOrElse { return errorResult(it.message) }
            val oldString = params.requiredString("oldString").getOrElse { return errorResult(it.message) }
            val newString = params.requiredString("newString").getOrElse { return errorResult(it.message) }
            if (oldString.isEmpty()) return errorResult("oldString must not be empty")

            val relative = workspace.validate(path).getOrElse { return errorResult(it.message) }
            val entry = workspace.stat(relative).getOrElse { return errorResult(it.message) }
            if (entry == null || entry.isDirectory) return errorResult("Not a file: $path")

            val text = workspace.readText(relative).getOrElse { return errorResult(it.message) }
            var count = 0
            var index = text.indexOf(oldString)
            while (index >= 0) {
                count++
                index = text.indexOf(oldString, index + oldString.length)
            }
            if (count == 0) return errorResult("oldString not found")
            if (count > 1) {
                return errorResult("found $count occurrences; provide a unique oldString")
            }

            val updated = text.replaceFirst(oldString, newString)
            workspace.writeText(relative, updated).getOrElse { return errorResult(it.message) }
            ToolResult(AgentToolStatus.OK, "Edited $relative")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorResult(e.message)
        }
    }
}

private fun isTextCandidate(name: String): Boolean {
    val dot = name.lastIndexOf('.')
    if (dot < 0 || dot == name.length - 1) return true
    return name.substring(dot + 1).lowercase(Locale.ROOT) in TEXT_EXTENSIONS
}

private val LIST_SCHEMA = """
    {
      "type": "object",
      "properties": {
        "path": {
          "type": "string",
          "description": "Directory path relative to the sandbox; defaults to ."
        }
      }
    }
""".trimIndent()

private val FIND_SCHEMA = """
    {
      "type": "object",
      "properties": {
        "pattern": {
          "type": "string",
          "description": "Glob pattern matched against sandbox-relative paths."
        },
        "path": {
          "type": "string",
          "description": "Directory path relative to the sandbox; defaults to ."
        }
      },
      "required": ["pattern"]
    }
""".trimIndent()

private val GREP_SCHEMA = """
    {
      "type": "object",
      "properties": {
        "pattern": {
          "type": "string",
          "description": "Regular expression, or a literal string when the regex is invalid."
        },
        "path": {
          "type": "string",
          "description": "File or directory path relative to the sandbox; defaults to ."
        },
        "maxResults": {
          "type": "integer",
          "description": "Maximum number of matches (default 50, max 200)."
        }
      },
      "required": ["pattern"]
    }
""".trimIndent()

private val READ_SCHEMA = """
    {
      "type": "object",
      "properties": {
        "path": {
          "type": "string",
          "description": "File path relative to the sandbox."
        },
        "offset": {
          "type": "integer",
          "description": "First line to read (1-based); defaults to 1."
        },
        "limit": {
          "type": "integer",
          "description": "Maximum lines to read (default 200, max 2000)."
        }
      },
      "required": ["path"]
    }
""".trimIndent()

private val WRITE_SCHEMA = """
    {
      "type": "object",
      "properties": {
        "path": {
          "type": "string",
          "description": "File path relative to the sandbox."
        },
        "content": {
          "type": "string",
          "description": "UTF-8 text to write."
        }
      },
      "required": ["path", "content"]
    }
""".trimIndent()

private val EDIT_SCHEMA = """
    {
      "type": "object",
      "properties": {
        "path": {
          "type": "string",
          "description": "File path relative to the sandbox."
        },
        "oldString": {
          "type": "string",
          "description": "Exact text to replace; must occur exactly once."
        },
        "newString": {
          "type": "string",
          "description": "Replacement text."
        }
      },
      "required": ["path", "oldString", "newString"]
    }
""".trimIndent()

internal fun fileTools(workspace: AgentWorkspace): List<AgentTool> = listOf(
    ListTool(workspace),
    FindTool(workspace),
    GrepTool(workspace),
    ReadTool(workspace),
    WriteTool(workspace),
    EditTool(workspace),
)
