package com.sbro.emucored.ui.formats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sbro.emucored.R
import com.sbro.emucored.core.N3dsGameFormats
import com.sbro.emucored.ui.common.ScreenTopBar
import com.sbro.emucored.ui.common.appScreenTopPadding
import com.sbro.emucored.ui.common.navigationBarsHorizontalPaddingValues
import com.sbro.emucored.ui.common.rememberDebouncedClick
import com.sbro.emucored.ui.theme.ScreenHorizontalPadding
import com.sbro.emucored.ui.theme.neon.neonShape

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SupportedFormatsScreen(
    onBackClick: (() -> Unit)? = null
) {
    val topInset = appScreenTopPadding()
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val horizontalSystemBarPadding = navigationBarsHorizontalPaddingValues()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontalSystemBarPadding)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp + bottomInset)
        ) {
            FormatsTopBar(topInset = topInset, onBackClick = onBackClick)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ScreenHorizontalPadding),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                FormatGroupCard(
                    icon = Icons.Rounded.Description,
                    title = stringResource(R.string.formats_psp_supported_title),
                    body = stringResource(R.string.formats_psp_supported_body),
                    formats = N3dsGameFormats.libraryFormats
                )

                FormatGroupCard(
                    icon = Icons.Rounded.Memory,
                    title = stringResource(R.string.formats_psp_recommended_title),
                    body = stringResource(R.string.formats_psp_recommended_body),
                    formats = N3dsGameFormats.libraryFormats + N3dsGameFormats.homebrewFormats
                )

                FormatGroupCard(
                    icon = Icons.Rounded.FolderZip,
                    title = stringResource(R.string.formats_psp_homebrew_title),
                    body = stringResource(R.string.formats_psp_homebrew_body),
                    formats = N3dsGameFormats.homebrewFormats
                )
            }
        }
    }
}

@Composable
private fun FormatsTopBar(
    topInset: Dp,
    onBackClick: (() -> Unit)?
) {
    val handleBackClick = onBackClick?.let { rememberDebouncedClick(onClick = it) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = ScreenHorizontalPadding,
                end = ScreenHorizontalPadding,
                top = topInset,
                bottom = 14.dp
            ),
        shape = neonShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        shadowElevation = 6.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (handleBackClick != null) {
                ScreenTopBar(
                    title = stringResource(R.string.formats_title),
                    onBackClick = handleBackClick,
                    embedded = true
                )
            } else {
                Text(
                    text = stringResource(R.string.formats_title),
                    modifier = Modifier.padding(horizontal = 10.dp),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FormatGroupCard(
    icon: ImageVector,
    title: String,
    body: String,
    formats: List<String>
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = neonShape(28.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        tonalElevation = 4.dp,
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = neonShape(14.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                    tonalElevation = 2.dp
                ) {
                    Box(modifier = Modifier.padding(10.dp)) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                formats.forEach { format ->
                    Surface(
                        shape = neonShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f),
                        tonalElevation = 1.dp
                    ) {
                        Text(
                            text = format,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}
