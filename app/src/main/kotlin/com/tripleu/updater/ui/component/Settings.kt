package com.tripleu.updater.ui.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.focusable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment.Companion.CenterEnd
import androidx.compose.ui.Alignment.Companion.CenterStart
import androidx.compose.ui.Alignment.Companion.CenterVertically
import androidx.compose.ui.Alignment.Companion.Start
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tripleu.updater.R


@Composable
fun SliderSetting(
    getValue: () -> Float,
    setValue: (Float) -> Unit,
    text: String,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    @DrawableRes icon: Int
) {
    var position by remember { mutableFloatStateOf(getValue()) }
    LaunchedEffect(getValue()) {
        position = getValue()
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            verticalAlignment = CenterVertically
        ) {
            Icon(
                painterResource(id = icon),
                contentDescription = text,
                modifier = Modifier
                    .padding(end = 16.dp)
                    .size(24.dp)
            )
            Column(Modifier.weight(1f)) {
                Text(text = text)
                Text(
                    text = position.toInt().toString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row {
                IconButton(
                    onClick = {
                        val next = (position - 1f).coerceAtLeast(valueRange.start)
                        position = next
                        setValue(next)
                    },
                    modifier = Modifier.focusable()
                ) {
                    Icon(
                        Icons.Filled.Remove,
                        contentDescription = stringResource(R.string.slider_decrease, text)
                    )
                }
                IconButton(
                    onClick = {
                        val next = (position + 1f).coerceAtMost(valueRange.endInclusive)
                        position = next
                        setValue(next)
                    },
                    modifier = Modifier.focusable()
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = stringResource(R.string.slider_increase, text)
                    )
                }
            }
        }
        Slider(
            value = position,
            onValueChange = { newValue ->
                position = newValue
                setValue(newValue)
            },
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SegmentedButtonSetting(
    text: String,
    options: List<String>,
    getValue: () -> Int,
    setValue: (Int) -> Unit,
    @DrawableRes icon: Int = R.drawable.ic_system
) = Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
    var position by remember { mutableIntStateOf(getValue()) }
    Icon(painterResource(id = icon), text, Modifier.align(CenterVertically))
    Column(Modifier.padding(start = 8.dp).fillMaxWidth()) {
        Text(text, Modifier.align(Start).padding(start = 8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.padding(8.dp).fillMaxWidth()) {
            options.forEachIndexed { index, label ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    onClick = {
                        position = index
                        setValue(position)
                    },
                    selected = index == position
) {
                    Text(label)
                }
            }
        }
    }
}

@Composable
fun SwitchSetting(
    getValue: () -> Boolean,
    setValue: (Boolean) -> Unit,
    text: String,
    @DrawableRes icon: Int = R.drawable.ic_system,
    description: String? = null
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (focused) MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
                else Color.Transparent
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable()
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Row(Modifier.align(CenterStart)) {
                Icon(
                    painterResource(id = icon),
                    text,
                    Modifier.align(CenterVertically).padding(end = 16.dp).size(24.dp)
                )
                Text(text, Modifier.align(CenterVertically))
            }
            var value by remember { mutableStateOf(getValue()) }
            Switch(
                checked = value,
                onCheckedChange = {
                    setValue(it)
                    value = getValue()
                },
                modifier = Modifier.align(CenterEnd).focusable()
            )
        }
        if (description != null) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 40.dp, top = 4.dp, end = 0.dp)
            )
        }
    }
}

@Suppress("unused")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DropDownSetting(
    text: String,
    options: List<String>,
    getValue: () -> Int,
    setValue: (Int) -> Unit,
    @DrawableRes icon: Int,
    width: Int = 100
) {
    var expanded by remember { mutableStateOf(false) }
    var selectedOptionText by remember { mutableStateOf(options[getValue()]) }

    Box(
        Modifier
            .padding(top = 16.dp, start = 16.dp, end = 16.dp, bottom = 8.dp)
            .fillMaxWidth()
    ) {
        Row(Modifier.align(CenterStart)) {
            Icon(
                painterResource(id = icon),
                contentDescription = text,
                modifier = Modifier.align(CenterVertically).padding(end = 16.dp).size(24.dp)
            )
            Text(text, Modifier.align(CenterVertically))
        }

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
            modifier = Modifier
                .align(CenterEnd)
                .focusable()
                .width(width.dp)
        ) {
            OutlinedTextField(
                readOnly = true,
                value = selectedOptionText,
                onValueChange = {},
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable),
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                colors = ExposedDropdownMenuDefaults.textFieldColors(),
                singleLine = true
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEachIndexed { i, option ->
                    DropdownMenuItem(
                        text = { Text(text = option) },
                        onClick = {
                            selectedOptionText = option
                            expanded = false
                            setValue(i)
                        }
                    )
                }
            }
        }
    }
}

@Suppress("unused")
@Composable
fun TextFieldSetting(
    text: String,
    valueRange: IntRange = 0..23,
    getValue: () -> Int,
    setValue: (Int) -> Unit,
    @DrawableRes icon: Int
) = Box(Modifier.padding(top = 16.dp, start = 16.dp, end = 16.dp, bottom = 8.dp).fillMaxWidth()) {

    var value by remember { mutableStateOf(getValue().toString()) }

    Row(Modifier.align(CenterStart)) {
        Icon(
            painterResource(id = icon),
            text,
            Modifier.align(CenterVertically).padding(end = 16.dp).size(24.dp)
        )
        Text(text,  Modifier.align(CenterVertically))
    }
    OutlinedTextField(
        modifier = Modifier
            .align(CenterEnd)
            .width(100.dp)
            .onFocusChanged { if (!it.hasFocus && value == "") value = getValue().toString() },
        value = value,
        singleLine = true,
        maxLines = 1,
        textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.End),
        onValueChange = {
            var new = it.toIntOrNull()
            if (new != null) {
                if (new < valueRange.first) {
                    new = valueRange.first
                } else if (new > valueRange.last) {
                    new = valueRange.last
                }
                value = new.toString()
                setValue(new)
            } else {
                value = ""
            }
        },
        keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
    )
}

@Composable
fun ButtonSetting(
    text: String,
    onClick: () -> Unit,
    @DrawableRes icon: Int,
    @DrawableRes iconButton: Int
) = Row(
    Modifier
        .fillMaxWidth()
        .height(70.dp)
        .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
) {
    Icon(painterResource(id = icon), text, Modifier.align(CenterVertically))
    Text(text, Modifier.align(CenterVertically).padding(start = 16.dp))
    Spacer(Modifier.weight(1f))
    IconButton(onClick = onClick) {
        Icon(painterResource(iconButton), stringResource(R.string.copy_to_clipboard))
    }
}
