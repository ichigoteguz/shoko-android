package dev.shoko.remote

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shoko WebUI 2.5.13, src/css/theme-shoko-gray.css. */
object ShokoColors {
    val Background = Color(0xFF282E38)
    val Panel = Color(0xFF2C333E)
    val Navigation = Color(0xFF353D4A)
    val Input = Color(0xFF1E2027)
    val Text = Color(0xFFCFD8E3)
    val Primary = Color(0xFF44A3FF)
    val OnPrimary = Color(0xFF010F1C)
    val Border = Color(0xFF21242B)
    val Selected = Color(0xFF283654)
    val Success = Color(0xFF10C469)
    val Warning = Color(0xFFF9C851)
    val Error = Color(0xFFFF6C6C)
}

@OptIn(ExperimentalTextApi::class)
private val Sora = FontFamily(
    Font(R.font.sora, FontWeight.Light, variationSettings = FontVariation.Settings(FontVariation.weight(300))),
    Font(R.font.sora, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.sora, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.sora, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.sora, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
)

private fun TextStyle.shoko() = copy(fontFamily = Sora)
private val ShokoTypography = Typography().let { original ->
    Typography(
        displayLarge = original.displayLarge.shoko(), displayMedium = original.displayMedium.shoko(), displaySmall = original.displaySmall.shoko(),
        headlineLarge = original.headlineLarge.shoko().copy(fontSize = 30.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold),
        headlineMedium = original.headlineMedium.shoko().copy(fontSize = 26.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold),
        headlineSmall = original.headlineSmall.shoko(),
        titleLarge = original.titleLarge.shoko().copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = original.titleMedium.shoko().copy(fontWeight = FontWeight.SemiBold), titleSmall = original.titleSmall.shoko(),
        bodyLarge = original.bodyLarge.shoko().copy(lineHeight = 25.6.sp), bodyMedium = original.bodyMedium.shoko(), bodySmall = original.bodySmall.shoko(),
        labelLarge = original.labelLarge.shoko().copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
        labelMedium = original.labelMedium.shoko(), labelSmall = original.labelSmall.shoko(),
    )
}

private val ShokoColorScheme = darkColorScheme(
    primary = ShokoColors.Primary, onPrimary = ShokoColors.OnPrimary,
    primaryContainer = ShokoColors.Selected, onPrimaryContainer = ShokoColors.Text,
    secondary = ShokoColors.Text, onSecondary = ShokoColors.OnPrimary,
    secondaryContainer = ShokoColors.Navigation, onSecondaryContainer = ShokoColors.Text,
    tertiary = Color(0xFFB981FF), onTertiary = ShokoColors.OnPrimary,
    tertiaryContainer = ShokoColors.Selected, onTertiaryContainer = ShokoColors.Text,
    background = ShokoColors.Background, onBackground = ShokoColors.Text,
    surface = ShokoColors.Panel, onSurface = ShokoColors.Text,
    surfaceVariant = ShokoColors.Navigation, onSurfaceVariant = ShokoColors.Text.copy(alpha = .78f),
    surfaceTint = ShokoColors.Primary,
    surfaceDim = ShokoColors.Background, surfaceBright = ShokoColors.Navigation,
    surfaceContainerLowest = ShokoColors.Input, surfaceContainerLow = ShokoColors.Background,
    surfaceContainer = ShokoColors.Panel, surfaceContainerHigh = ShokoColors.Navigation, surfaceContainerHighest = ShokoColors.Navigation,
    outline = ShokoColors.Border, outlineVariant = ShokoColors.Border,
    error = ShokoColors.Error, onError = ShokoColors.OnPrimary,
    errorContainer = Color(0xFF4A303A), onErrorContainer = ShokoColors.Text,
    inverseSurface = ShokoColors.Text, inverseOnSurface = ShokoColors.Input, inversePrimary = ShokoColors.OnPrimary,
)

@Composable
fun ShokoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ShokoColorScheme, typography = ShokoTypography,
        shapes = Shapes(extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(8.dp),
            large = RoundedCornerShape(8.dp), extraLarge = RoundedCornerShape(8.dp)), content = content)
}

@Composable
fun shokoInputColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = ShokoColors.Input, unfocusedContainerColor = ShokoColors.Input, disabledContainerColor = ShokoColors.Input,
    focusedBorderColor = ShokoColors.Primary, unfocusedBorderColor = ShokoColors.Border, disabledBorderColor = ShokoColors.Border,
    focusedLabelColor = ShokoColors.Text, unfocusedLabelColor = ShokoColors.Text.copy(alpha = .78f),
)

@Composable
fun ShokoTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    OutlinedTextField(value, onValueChange, modifier = modifier, enabled = enabled, label = label,
        placeholder = placeholder, visualTransformation = visualTransformation, keyboardOptions = keyboardOptions,
        singleLine = singleLine, minLines = minLines, maxLines = maxLines, textStyle = textStyle,
        shape = RoundedCornerShape(8.dp), colors = shokoInputColors())
}

@Composable
fun ShokoButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    Button(onClick, modifier = modifier, enabled = enabled, shape = RoundedCornerShape(8.dp),
        border = BorderStroke(2.dp, ShokoColors.Border), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), content = content)
}

@Composable
fun ShokoSecondaryButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    OutlinedButton(onClick, modifier = modifier, enabled = enabled, shape = RoundedCornerShape(8.dp), border = BorderStroke(2.dp, ShokoColors.Border),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = ShokoColors.Navigation, contentColor = ShokoColors.Text), content = content)
}
