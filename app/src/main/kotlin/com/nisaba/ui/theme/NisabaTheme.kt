package com.nisaba.ui.theme

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode

/** No ripples: they ghost on e-ink. */
private object NoIndication : IndicationNodeFactory {
    override fun create(interactionSource: androidx.compose.foundation.interaction.InteractionSource): DelegatableNode =
        object : Modifier.Node(), DrawModifierNode {
            override fun ContentDrawScope.draw() = drawContent()
        }
    override fun equals(other: Any?) = other === NoIndication
    override fun hashCode() = 0
}

@Composable
fun NisabaTheme(content: @Composable () -> Unit) {
    val c = NisabaColors()
    val scheme = lightColorScheme(
        primary = c.ink, onPrimary = c.paper, secondary = c.lapis, onSecondary = c.paper,
        background = c.paper, onBackground = c.ink, surface = c.paper, onSurface = c.ink,
        outline = c.ink, outlineVariant = c.faint,
    )
    CompositionLocalProvider(LocalNisabaColors provides c, LocalIndication provides NoIndication) {
        MaterialTheme(colorScheme = scheme, typography = NisabaTypography, content = content)
    }
}

object Nisaba {
    val colors: NisabaColors @Composable get() = LocalNisabaColors.current
}
