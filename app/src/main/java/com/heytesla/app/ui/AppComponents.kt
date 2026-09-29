package com.heytesla.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

internal enum class AppSymbol {
    VEHICLE,
    PERMISSIONS,
    ASSISTANT,
    DIAGNOSTICS,
    BLUETOOTH,
    INFO,
}

@Composable
internal fun AppSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        content()
    }
}

@Composable
internal fun AppRow(
    title: String,
    subtitle: String,
    symbol: AppSymbol = AppSymbol.INFO,
    enabled: Boolean = true,
    expanded: Boolean? = null,
    onClick: () -> Unit,
) {
    val accordion = expanded != null
    val stateDescription = if (expanded == true) "펼쳐짐" else "접힘"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(
                if (accordion) {
                    Modifier.semantics { this.stateDescription = stateDescription }
                } else {
                    Modifier
                },
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = symbol.vector,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = when (expanded) {
                null -> "›"
                true -> "▾"
                false -> "▸"
            },
            modifier = Modifier.clearAndSetSemantics {},
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(0.42f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            modifier = Modifier.weight(0.58f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 접힌 상세 근거. 핵심 상태·행동은 접지 않고 보조 근거만 여기에 담는다. */
@Composable
internal fun AppDisclosure(
    expanded: Boolean,
    expandLabel: String,
    collapseLabel: String,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TextButton(
            onClick = onToggle,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(if (expanded) collapseLabel else expandLabel)
        }
        if (expanded) content()
    }
}

private val AppSymbol.vector: ImageVector
    get() = when (this) {
        AppSymbol.VEHICLE -> AppSymbols.Vehicle
        AppSymbol.PERMISSIONS -> AppSymbols.Permissions
        AppSymbol.ASSISTANT -> AppSymbols.Assistant
        AppSymbol.DIAGNOSTICS -> AppSymbols.Diagnostics
        AppSymbol.BLUETOOTH -> AppSymbols.Bluetooth
        AppSymbol.INFO -> AppSymbols.Info
    }

private object AppSymbols {
    val Vehicle = vector("Vehicle") {
        moveTo(3f, 13f)
        lineTo(5f, 7f)
        lineTo(19f, 7f)
        lineTo(21f, 13f)
        lineTo(21f, 18f)
        lineTo(18f, 18f)
        lineTo(18f, 16f)
        lineTo(6f, 16f)
        lineTo(6f, 18f)
        lineTo(3f, 18f)
        close()
        moveTo(7f, 13f)
        lineTo(9f, 13f)
        moveTo(15f, 13f)
        lineTo(17f, 13f)
    }
    val Permissions = vector("Permissions") {
        moveTo(12f, 2f)
        lineTo(20f, 5f)
        lineTo(20f, 11f)
        curveTo(20f, 16f, 16.6f, 20f, 12f, 22f)
        curveTo(7.4f, 20f, 4f, 16f, 4f, 11f)
        lineTo(4f, 5f)
        close()
        moveTo(8f, 12f)
        lineTo(10.5f, 14.5f)
        lineTo(16f, 9f)
    }
    val Assistant = vector("Assistant") {
        moveTo(12f, 3f)
        curveTo(9f, 3f, 7f, 5.2f, 7f, 8f)
        lineTo(7f, 12f)
        curveTo(7f, 14.8f, 9f, 17f, 12f, 17f)
        curveTo(15f, 17f, 17f, 14.8f, 17f, 12f)
        lineTo(17f, 8f)
        curveTo(17f, 5.2f, 15f, 3f, 12f, 3f)
        close()
        moveTo(5f, 11f)
        lineTo(5f, 12f)
        curveTo(5f, 15.8f, 8.1f, 19f, 12f, 19f)
        curveTo(15.9f, 19f, 19f, 15.8f, 19f, 12f)
        lineTo(19f, 11f)
        moveTo(12f, 19f)
        lineTo(12f, 22f)
    }
    val Diagnostics = vector("Diagnostics") {
        moveTo(4f, 19f)
        lineTo(9f, 13f)
        lineTo(12f, 16f)
        lineTo(20f, 6f)
        moveTo(4f, 5f)
        lineTo(4f, 19f)
        lineTo(20f, 19f)
    }
    val Bluetooth = vector("Bluetooth") {
        moveTo(12f, 2f)
        lineTo(17f, 7f)
        lineTo(12f, 12f)
        lineTo(17f, 17f)
        lineTo(12f, 22f)
        close()
        moveTo(7f, 7f)
        lineTo(17f, 17f)
        moveTo(7f, 17f)
        lineTo(17f, 7f)
    }
    val Info = vector("Info") {
        moveTo(12f, 2f)
        curveTo(6.5f, 2f, 2f, 6.5f, 2f, 12f)
        curveTo(2f, 17.5f, 6.5f, 22f, 12f, 22f)
        curveTo(17.5f, 22f, 22f, 17.5f, 22f, 12f)
        curveTo(22f, 6.5f, 17.5f, 2f, 12f, 2f)
        close()
        moveTo(12f, 10f)
        lineTo(12f, 17f)
        moveTo(12f, 7f)
        lineTo(12f, 7.2f)
    }
}

private fun vector(name: String, block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = SolidColor(Color.Transparent),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
        ) {
            block()
        }
    }.build()
