package dev.sun.wechat.ui.content.miuix

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

fun <T> LazyListScope.miuixLazySegmentedItems(
    items: List<T>,
    key: (T) -> Any,
    itemContent: @Composable (T) -> Unit,
) {
    itemsIndexed(items, key = { _, item -> key(item) }) { index, item ->
        Box(Modifier.animateItem().padding(top = if (index == 0) 0.dp else 6.dp)) {
            itemContent(item)
        }
    }
}
