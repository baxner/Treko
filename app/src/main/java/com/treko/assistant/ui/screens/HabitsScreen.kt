package com.treko.assistant.ui.screens

import com.treko.assistant.service.TrekoAssistantService
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.treko.assistant.R
import com.treko.assistant.ui.theme.CardBorder
import com.treko.assistant.ui.theme.NeonCyan
import com.treko.assistant.ui.theme.NeonPurple
import com.treko.assistant.ui.theme.SurfaceDark
import com.treko.assistant.ui.theme.TextPrimary
import com.treko.assistant.ui.theme.TextSecondary
import com.treko.core.common.data.TrekoHabitManager

@Composable
fun HabitsScreen() {
    val context = LocalContext.current
    val habitManager = remember(context) { TrekoHabitManager(context) }
    val habits by TrekoHabitManager.habitsFlow.collectAsState()
    val waterGlasses by TrekoHabitManager.waterGlassesFlow.collectAsState()

    val totalHabits = (habits.size + 1).coerceAtLeast(1)
    val waterDone = waterGlasses >= 8
    val completedHabits = habits.count { it.isDone } + (if (waterDone) 1 else 0)
    val rawProgress = (completedHabits.toFloat() / totalHabits.toFloat()).coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(
        targetValue = rawProgress,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "HabitsProgress"
    )

    var showAddDialog by remember { mutableStateOf(false) }
    var newHabitTitle by remember { mutableStateOf("") }

    fun submitNewHabit() {
        val clean = newHabitTitle.trim()
        if (clean.isNotEmpty()) {
            habitManager.addHabit(clean)
            newHabitTitle = ""
            showAddDialog = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(stringResource(R.string.habits_title), color = NeonCyan, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.habits_subtitle), color = TextSecondary, fontSize = 13.sp)
            }
            IconButton(
                onClick = { showAddDialog = true },
                modifier = Modifier.background(SurfaceDark, CircleShape)
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.habits_add_cd), tint = NeonCyan)
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.habits_today_progress), color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = stringResource(
                            R.string.habits_progress_format,
                            completedHabits,
                            totalHabits,
                            (rawProgress * 100).toInt()
                        ),
                        color = NeonCyan,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }

                LinearProgressIndicator(
                    progress = { animatedProgress },
                    color = NeonCyan,
                    trackColor = CardBorder,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                )
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("💧", fontSize = 28.sp)
                    Column {
                        Text(stringResource(R.string.habits_hydration_title), color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(
                            text = if (waterDone) {
                                stringResource(R.string.habits_hydration_done)
                            } else {
                                stringResource(R.string.habits_hydration_count, waterGlasses)
                            },
                            color = if (waterDone) Color(0xFF00E676) else TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IconButton(
                        onClick = { if (waterGlasses > 0) habitManager.setWaterGlasses(waterGlasses - 1) },
                        modifier = Modifier
                            .size(36.dp)
                            .background(CardBorder, CircleShape)
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.habits_decrease_cd), tint = TextPrimary, modifier = Modifier.size(18.dp))
                    }
                    Text(
                        text = "$waterGlasses",
                        color = NeonCyan,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                    IconButton(
                        onClick = { habitManager.setWaterGlasses(waterGlasses + 1) },
                        modifier = Modifier
                            .size(36.dp)
                            .background(CardBorder, CircleShape)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.habits_increase_cd), tint = TextPrimary, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        Text(stringResource(R.string.habits_section_title), color = NeonPurple, fontSize = 16.sp, fontWeight = FontWeight.Bold)

        AnimatedVisibility(visible = habits.isEmpty(), enter = fadeIn(), exit = fadeOut()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(
                    text = stringResource(R.string.habits_empty, TrekoAssistantService.assistantName.collectAsState().value),
                    color = TextSecondary,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                )
            }
        }

        habits.forEach { habit ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (habit.isDone) SurfaceDark.copy(alpha = 0.7f) else SurfaceDark
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { habitManager.toggleHabit(habit.id) },
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(habit.icon, fontSize = 22.sp)
                        Text(
                            text = habit.title,
                            color = if (habit.isDone) TextSecondary else TextPrimary,
                            textDecoration = if (habit.isDone) TextDecoration.LineThrough else TextDecoration.None,
                            fontSize = 14.sp
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = habit.isDone,
                            onCheckedChange = { _ -> habitManager.toggleHabit(habit.id) },
                            colors = CheckboxDefaults.colors(
                                checkedColor = NeonCyan,
                                checkmarkColor = Color.Black
                            )
                        )
                        IconButton(
                            onClick = { habitManager.deleteHabit(habit.id) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.DeleteOutline,
                                contentDescription = stringResource(R.string.action_delete),
                                tint = TextSecondary.copy(alpha = 0.6f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text(stringResource(R.string.habits_dialog_title), color = NeonCyan) },
            text = {
                OutlinedTextField(
                    value = newHabitTitle,
                    onValueChange = { newHabitTitle = it },
                    label = { Text(stringResource(R.string.habits_dialog_label)) },
                    placeholder = { Text(stringResource(R.string.habits_dialog_placeholder)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submitNewHabit() }),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = { submitNewHabit() },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black)
                ) {
                    Text(stringResource(R.string.action_add))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text(stringResource(R.string.action_cancel), color = TextSecondary)
                }
            },
            containerColor = SurfaceDark
        )
    }
}
