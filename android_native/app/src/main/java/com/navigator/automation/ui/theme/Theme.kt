package com.navigator.automation.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Terracotta / sage / cream palette
private val Terra        = Color(0xFFBF6B52)   // primary
private val TerraLight   = Color(0xFFF5C4AB)   // primary container
private val TerraOnCont  = Color(0xFF3D1800)   // on primary container

private val Sage         = Color(0xFF5E8B72)   // secondary
private val SageLight    = Color(0xFFC0DDD0)   // secondary container
private val SageOnCont   = Color(0xFF1A3829)   // on secondary container

private val Cream        = Color(0xFFF5EDD8)   // background
private val CreamSurface = Color(0xFFFFF8F0)   // surface
private val OnBg         = Color(0xFF3D2419)   // on background / on surface

private val LightColors = lightColorScheme(
    primary                = Terra,
    onPrimary              = Color.White,
    primaryContainer       = TerraLight,
    onPrimaryContainer     = TerraOnCont,
    secondary              = Sage,
    onSecondary            = Color.White,
    secondaryContainer     = SageLight,
    onSecondaryContainer   = SageOnCont,
    background             = Cream,
    onBackground           = OnBg,
    surface                = CreamSurface,
    onSurface              = OnBg,
    surfaceVariant         = Color(0xFFEEE2D3),
    onSurfaceVariant       = Color(0xFF5C4433),
    error                  = Color(0xFFBA1A1A),
    outline                = Color(0xFF9C7E6D)
)

@Composable
fun AppTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = LightColors,
        content = content
    )
}
