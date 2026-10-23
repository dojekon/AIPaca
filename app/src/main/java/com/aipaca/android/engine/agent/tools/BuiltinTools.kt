package com.aipaca.android.engine.agent.tools

import com.aipaca.android.engine.agent.AgentTool
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

fun defaultFetchClient(): OkHttpClient =
    OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

fun agentSandboxRoot(filesDir: File): File = File(filesDir, "agent")

fun builtinTools(
    workspace: AgentWorkspace,
    fetchClient: OkHttpClient = defaultFetchClient(),
    fetchPageChars: () -> Int = { FETCH_DEFAULT_PAGE_CHARS },
): List<AgentTool> = fileTools(workspace) + FetchTool(fetchClient, fetchPageChars)

fun builtinTools(
    sandboxRoot: File,
    fetchClient: OkHttpClient = defaultFetchClient(),
    fetchPageChars: () -> Int = { FETCH_DEFAULT_PAGE_CHARS },
): List<AgentTool> = builtinTools(FileWorkspace(sandboxRoot), fetchClient, fetchPageChars)
