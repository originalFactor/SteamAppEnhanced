package cc.ptoe.steam.enhanced.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cc.ptoe.steam.enhanced.R
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.utils.overScrollVertical

/** Owns the viewport and scroll container so short/empty pages can also be pulled. */
@Composable
internal fun RefreshableColumn(
    work: AppWork,
    onRefresh: suspend () -> Unit,
    scrollBehavior: ScrollBehavior? = null,
    initialRefresh: Boolean = false,
    refreshKey: Any? = Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    var refreshing by remember { mutableStateOf(false) }
    fun refresh() {
        work.run {
            refreshing = true
            try { onRefresh() } finally { refreshing = false }
        }
    }
    LaunchedEffect(refreshKey) { if (initialRefresh) refresh() }
    PullToRefresh(
        isRefreshing = refreshing,
        onRefresh = ::refresh,
        modifier = Modifier.widthIn(max = 660.dp).fillMaxSize(),
        topAppBarScrollBehavior = scrollBehavior,
        refreshTexts = listOf(
            stringResource(R.string.ui_pull_refresh), stringResource(R.string.ui_release_refresh),
            stringResource(R.string.ui_refreshing), stringResource(R.string.ui_refresh_finished),
        ),
    ) {
        Column(
            Modifier.overScrollVertical().fillMaxSize().verticalScroll(rememberScrollState(), overscrollEffect = null)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            work.feedback?.let { Text(it) }
            content()
        }
    }
}
