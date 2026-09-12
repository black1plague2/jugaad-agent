package com.jugaad.agent.ui.common.fiori

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Solid crimson call-to-action, the app's one primary action colour. Deepens to [FioriColors.BrandPressed] while pressed. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = FioriControlRadius,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (pressed) FioriColors.BrandPressed else FioriColors.Brand,
            contentColor = FioriColors.TextPrimary,
            disabledContainerColor = FioriColors.Brand.copy(alpha = 0.35f),
            disabledContentColor = FioriColors.TextPrimary.copy(alpha = 0.6f),
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        interactionSource = interactionSource,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}

/** Raised-fill, hairline-bordered secondary action. */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = FioriControlRadius,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = FioriColors.Surface,
            contentColor = FioriColors.TextPrimary,
            disabledContainerColor = FioriColors.Surface,
            disabledContentColor = FioriColors.TextDisabled,
        ),
        border = BorderStroke(1.dp, FioriColors.Hairline),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}
