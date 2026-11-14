package com.treko.assistant.ui.screens

import com.treko.assistant.service.TrekoAssistantService
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.treko.assistant.ui.theme.NeonCyan
import com.treko.assistant.ui.theme.SurfaceDark
import com.treko.assistant.ui.theme.TextPrimary
import com.treko.assistant.ui.theme.TextSecondary
import com.treko.core.common.data.TrekoTaskManager

@Composable
fun TasksScreen() {
    val context = LocalContext.current
    val taskManager = remember(context) { TrekoTaskManager(context) }
    val tasks by TrekoTaskManager.tasksFlow.collectAsState()

    var newTaskText by remember { mutableStateOf("") }
    var filterMode by remember { mutableIntStateOf(0) } // 0: All, 1: Pending, 2: Done

    val filteredTasks = tasks.filter {
        when (filterMode) {
            1 -> !it.isDone
            2 -> it.isDone
            else -> true
        }
    }

    val doneCount = tasks.count { it.isDone }

    fun submitNewTask() {
        val clean = newTaskText.trim()
        if (clean.isNotEmpty()) {
            taskManager.addTask(clean)
            newTaskText = ""
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column {
            Text(stringResource(R.string.tasks_title), color = NeonCyan, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.tasks_completed_count, doneCount, tasks.size), color = TextSecondary, fontSize = 13.sp)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = newTaskText,
                onValueChange = { newTaskText = it },
                placeholder = { Text(stringResource(R.string.tasks_input_placeholder), fontSize = 14.sp) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submitNewTask() }),
                shape = RoundedCornerShape(12.dp)
            )

            Button(
                onClick = { submitNewTask() },
                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.height(56.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.action_add))
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = filterMode == 0,
                onClick = { filterMode = 0 },
                label = { Text(stringResource(R.string.tasks_filter_all, tasks.size), fontSize = 12.sp) }
            )
            FilterChip(
                selected = filterMode == 1,
                onClick = { filterMode = 1 },
                label = { Text(stringResource(R.string.tasks_filter_pending, tasks.count { !it.isDone }), fontSize = 12.sp) }
            )
            FilterChip(
                selected = filterMode == 2,
                onClick = { filterMode = 2 },
                label = { Text(stringResource(R.string.tasks_filter_done, doneCount), fontSize = 12.sp) }
            )
        }

        if (filteredTasks.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = when (filterMode) {
                        1 -> stringResource(R.string.tasks_empty_pending)
                        2 -> stringResource(R.string.tasks_empty_done)
                        else -> stringResource(R.string.tasks_empty_all, TrekoAssistantService.assistantName.collectAsState().value)
                    },
                    color = TextSecondary,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filteredTasks, key = { it.id }) { task ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { taskManager.toggleTask(task.id) },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Checkbox(
                                    checked = task.isDone,
                                    onCheckedChange = { _ -> taskManager.toggleTask(task.id) },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = NeonCyan,
                                        checkmarkColor = Color.Black
                                    )
                                )
                                Text(
                                    text = task.title,
                                    color = if (task.isDone) TextSecondary else TextPrimary,
                                    textDecoration = if (task.isDone) TextDecoration.LineThrough else TextDecoration.None,
                                    fontSize = 14.sp
                                )
                            }

                            IconButton(
                                onClick = { taskManager.deleteTask(task.id) }
                            ) {
                                Icon(
                                    Icons.Default.DeleteOutline,
                                    contentDescription = stringResource(R.string.action_delete),
                                    tint = TextSecondary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
