package com.aipaca.android.ui.destinations

import androidx.annotation.StringRes
import com.aipaca.android.R

sealed class Destinations(val route: String, @param:StringRes val labelRes: Int) {

    data object Chat : Destinations("chat", R.string.menu_new_chat)

    data object Models : Destinations("models", R.string.menu_models)

    data object Search : Destinations("search", R.string.menu_search)

    data object Server : Destinations("server", R.string.menu_server)

    data object Bench : Destinations("bench", R.string.menu_bench)

    data object Settings : Destinations("settings", R.string.menu_settings)

    data object About : Destinations("about", R.string.menu_about)

    companion object {

        const val CHAT_ID_ARG = "chatId"
        const val CHAT_BY_ID_ROUTE = "chat/{chatId}"

        fun chatRoute(chatId: Long): String = "chat/$chatId"

        val start: Destinations = Chat

        val menuItems: List<Destinations> =
            listOf(Models, Server, Bench, Settings, About)

        val all: List<Destinations> =
            listOf(Chat, Models, Search, Server, Bench, Settings, About)
    }
}
