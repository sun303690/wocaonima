package dev.ujhhgtg.wekit.ui.panel

import dev.ujhhgtg.wekit.features.items.chat.panel.StickerDestination

data class StickerPanelNavigation(
    val destination: StickerDestination,
    val selectedLocalPackId: String?,
    val localPackDetailId: String?,
    val showingMyUploads: Boolean,
    val selectedOnlinePackId: String?,
)

internal object PanelNavigationMemory {
    var sticker: StickerPanelNavigation? = null

    fun clear() {
        sticker = null
    }
}
