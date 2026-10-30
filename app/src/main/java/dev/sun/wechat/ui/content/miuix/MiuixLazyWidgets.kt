package dev.sun.wechat.ui.content.miuix

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sun.wechat.ui.content.m3.lazySegmentedItems as m3LazySegmentedItems

fun <T> LazyListScope.miuixLazySegmentedItems(
    items: List<T>,
    key: (T) -> Any,
    itemContent: @Composable (T) -> Unit,
) {
    if (material3EngineCurrent()) {
        this.m3LazySegmentedItems(items = items, key = key, itemContent = itemContent)
        return
    }
    itemsIndexed(items, key = { _, item -> key(item) }) { index, item ->
        Box(Modifier.animateItem().padding(top = if (index == 0) 0.dp else 6.dp)) {
            itemContent(item)
        }
    }
}
