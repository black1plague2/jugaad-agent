package com.jugaad.agent.ui.common.fiori

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow

/** One [BottomNav] destination. */
data class NavItem(val label: String, val icon: ImageVector)

/**
 * The federated shell's 4-item bottom navigation: [FioriColors.Surface] container,
 * selected icon and label in Brand crimson, unselected in TextSecondary, no
 * indicator pill behind the selected item.
 */
@Composable
fun BottomNav(items: List<NavItem>, selected: Int, onSelect: (Int) -> Unit) {
    NavigationBar(containerColor = FioriColors.Surface, contentColor = FioriColors.TextSecondary) {
        items.forEachIndexed { index, item ->
            val isSelected = index == selected
            NavigationBarItem(
                selected = isSelected,
                onClick = { onSelect(index) },
                icon = { Icon(item.icon, contentDescription = item.label) },
                label = {
                    Text(
                        item.label,
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = FioriColors.Brand,
                    selectedTextColor = FioriColors.Brand,
                    unselectedIconColor = FioriColors.TextSecondary,
                    unselectedTextColor = FioriColors.TextSecondary,
                    indicatorColor = Color.Transparent,
                ),
            )
        }
    }
}
