package com.hhst.youtubelite.ui.design

import android.app.AlertDialog
import androidx.core.graphics.drawable.toDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckedTextView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.ui.theme.appDialogContext

@Composable
fun <T> SelectField(
    tag: String,
    selected: T,
    options: List<T>,
    labelOf: @Composable (T) -> String,
    minWidth: Float,
    chipHeight: Dp,
    onSelect: (T) -> Unit,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = DesignTokens.text(14, 20)
    val labels = options.map { labelOf(it) }
    val selectedLabel = labelOf(selected)
    val width = remember(labels, style, density, minWidth) {
        val labelWidth = labels.maxOf { measurer.measure(it, style).size.width / density.density }
        maxOf(minWidth, labelWidth + 46f)
    }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val palette = LocalAppColors.current
    val dark = palette != LightAppPalette
    val select by rememberUpdatedState(onSelect)
    Box {
        Row(
            Modifier.width(width.dp).height(chipHeight).testTag(tag)
                .clip(RoundedCornerShape(8.dp))
                .background(if (dark) DesignTokens.Surface else Color(0xFFF1F1F1))
                .border(
                    1.dp, DesignTokens.Border.copy(alpha = 0.2f),
                    RoundedCornerShape(8.dp),
                )
                .clickable(role = Role.Button) { expanded = true }
                .semantics { contentDescription = selectedLabel }
                .padding(start = 11.dp, end = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppText(
                selectedLabel, Modifier.weight(1f).testTag("$tag-label"), size = 14,
                line = 20, maxLines = 1,
            )
            AppIcon(AppIcon.ChevronDown, Modifier.size(24.dp).testTag("$tag-icon"))
        }
        if (expanded) DisposableEffect(context, options, labels, selected, palette) {
            val pickerContext = context.appDialogContext(darkTheme = dark)
            val scale = pickerContext.resources.displayMetrics.density
            val adapter = object : ArrayAdapter<String>(pickerContext, android.R.layout.simple_list_item_single_choice, labels) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                    (super.getView(position, convertView, parent) as CheckedTextView).apply {
                        textSize = 20f
                        minimumHeight = (64 * scale).toInt()
                        val inset = (12 * scale).toInt()
                        setPadding(inset, inset, inset, inset)
                    }
            }
            val dialog = AlertDialog.Builder(pickerContext)
                .setSingleChoiceItems(adapter, options.indexOf(selected)) { picker, index ->
                    expanded = false
                    select(options[index])
                    picker.dismiss()
                }
                .setOnDismissListener { expanded = false }
                .create()
            dialog.show()
            dialog.listView.setPadding(0, 0, 0, 0)
            dialog.listView.divider = palette.border.toArgb().toDrawable()
            dialog.listView.dividerHeight = scale.toInt().coerceAtLeast(1)
            onDispose {
                dialog.setOnDismissListener(null)
                dialog.dismiss()
            }
        }
    }
}
