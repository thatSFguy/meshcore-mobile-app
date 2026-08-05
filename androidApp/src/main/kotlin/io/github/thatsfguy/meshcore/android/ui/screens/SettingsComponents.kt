package io.github.thatsfguy.meshcore.android.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Shared settings-screen building blocks, used by BOTH the local
 * device Settings tab and the remote repeater/room settings editor so
 * the two surfaces look and behave identically: collapsible sections
 * that query their values on expand, the same rows, chips, hints and
 * wrap-safe button rows.
 */

/** Collapsible titled section with a divider, collapsed by default. */
@Composable
fun ExpandableSection(
    title: String,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(bottom = 8.dp)) { content() }
        }
        HorizontalDivider()
    }
}

/**
 * Query-on-expand: runs [query] when the section's content enters
 * composition (i.e. on every expand — AnimatedVisibility disposes
 * collapsed content) and shows a spinner until it answers. [enabled]
 * false skips the query so the caller's "connect / log in first" hint
 * renders immediately.
 */
@Composable
fun QueryOnExpand(
    enabled: Boolean,
    query: suspend () -> Unit,
    content: @Composable () -> Unit,
) {
    var loading by remember { mutableStateOf(enabled) }
    LaunchedEffect(Unit) {
        if (enabled) query()
        loading = false
    }
    if (loading) {
        SectionSpinner()
    } else {
        content()
    }
}

@Composable
fun SectionSpinner(label: String = "Querying radio…") {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 12.dp),
    ) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        HintText(label)
    }
}

@Composable
fun SettingRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
fun HintText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Body text inside an [ExpandableHint]. Distinct from [HintText]
 * because it is the only place in the app that may run to a paragraph
 * — the user asked for it by tapping "More".
 */
@Composable
fun DetailText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * One line of hint, with the rest of the truth behind a tap.
 *
 * This app's instinct to say the whole honest thing is right and worth
 * keeping — but it was spent three sentences at a time on every row of
 * every screen until the whole app read like a disclaimer, which is
 * its own kind of dishonesty because nobody reads a wall
 * (LESSONS §14, REBUILD-PLAYBOOK §6.3).
 *
 * The budget is one line per control. [summary] must be true on its
 * own — this is not a teaser for the real warning, it is the short
 * version of it. Anything that is dangerous to miss belongs in
 * [summary], not in [detail].
 */
@Composable
fun ExpandableHint(summary: String, detail: @Composable () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f, fill = false),
            )
            // 48dp minimum. The first cut zeroed the vertical content
            // padding to make the row compact, which produced a ~27x16dp
            // target sitting inline inside a scrollable — small enough to
            // miss, and easy to fire by accident while scrolling.
            TextButton(
                onClick = { expanded = !expanded },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier = Modifier.defaultMinSize(minWidth = 64.dp, minHeight = 48.dp),
            ) {
                Text(
                    if (expanded) "Less" else "More",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(bottom = 8.dp)) { detail() }
        }
    }
}

/** Single-choice chip row; FlowRow so chips wrap as whole units. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChoiceChips(
    options: List<String>,
    selected: Int,
    enabled: Boolean = true,
    onSelect: (Int) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEachIndexed { i, label ->
            FilterChip(
                selected = selected == i,
                onClick = { if (enabled) onSelect(i) },
                label = { Text(label) },
                enabled = enabled,
            )
        }
    }
}

/** Buttons that must never be squeezed into vertical text: each keeps
 *  its intrinsic width and overflow wraps to the next line. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ButtonFlowRow(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) { content() }
}

@Composable
fun SettingsTextField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    sensitive: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (sensitive) PasswordVisualTransformation()
        else VisualTransformation.None,
        modifier = modifier.fillMaxWidth().padding(vertical = 2.dp),
    )
}
