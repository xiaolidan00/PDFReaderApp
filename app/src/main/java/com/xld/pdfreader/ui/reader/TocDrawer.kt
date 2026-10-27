package com.xld.pdfreader.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DrawerDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xld.pdfreader.R

// 与 Material3 ModalDrawerSheet 保持一致：最小 240.dp，最大 DrawerDefaults.MaximumDrawerWidth(360.dp)
private val TocDrawerMinWidth = 240.dp

@Composable
fun TocDrawer(
    toc: List<TocItem>,
    onTocClick: (TocItem) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .sizeIn(minWidth = TocDrawerMinWidth, maxWidth = DrawerDefaults.MaximumDrawerWidth)
            .fillMaxHeight(),
        shape = DrawerDefaults.shape,
        color = DrawerDefaults.modalContainerColor,
        tonalElevation = DrawerDefaults.ModalDrawerElevation
    ) {
        Column(
            // edge-to-edge 下 Drawer 铺满整屏，Surface 背景可延伸到系统栏区域，
            // 但内容需避开顶部状态栏与底部导航栏，防止被遮盖
            modifier = Modifier
                .fillMaxHeight()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            Text(
                text = stringResource(R.string.reader_toc),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(16.dp)
            )
            HorizontalDivider()
            if (toc.isEmpty()) {
                Text(
                    text = stringResource(R.string.reader_toc_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                // weight(1f)：占满标题以下的剩余高度，长目录在抽屉内滚动，不会溢出到底部导航栏区域
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(toc, key = { "${it.level}-${it.page}-${it.title}" }) { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onTocClick(item) }
                                .padding(
                                    start = 16.dp + 16.dp * item.level,
                                    end = 16.dp,
                                    top = 12.dp,
                                    bottom = 12.dp
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = item.page.toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
