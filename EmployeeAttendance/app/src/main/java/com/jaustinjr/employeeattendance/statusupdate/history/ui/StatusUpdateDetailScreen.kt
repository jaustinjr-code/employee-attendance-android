package com.jaustinjr.employeeattendance.statusupdate.history.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jaustinjr.employeeattendance.R
import com.jaustinjr.employeeattendance.statusupdate.history.AnsweredQuestion
import com.jaustinjr.employeeattendance.statusupdate.history.StatusUpdateDetailViewModel
import com.jaustinjr.employeeattendance.statusupdate.history.StatusUpdateShift
import com.jaustinjr.employeeattendance.statusupdate.StatusUpdateQuestion
import com.jaustinjr.employeeattendance.ui.theme.EmployeeAttendanceTheme

/** Read-only view of one past status update, with a way into editing it. */
@Composable
fun StatusUpdateDetailScreen(
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StatusUpdateDetailViewModel = viewModel(factory = StatusUpdateDetailViewModel.Factory),
) {
    val shift by viewModel.shift.collectAsStateWithLifecycle()
    StatusUpdateDetailContent(shift = shift, onEdit = onEdit, modifier = modifier)
}

@Composable
fun StatusUpdateDetailContent(
    shift: StatusUpdateShift?,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (shift == null) {
            Text(
                text = stringResource(R.string.status_update_detail_missing),
                style = MaterialTheme.typography.bodyLarge,
            )
            return@Column
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = dayLabel(shift.clockOutAtMillis), style = MaterialTheme.typography.titleLarge)
                Text(text = shiftTimeRange(shift), style = MaterialTheme.typography.titleSmall)
                Text(
                    text = shiftWorksite(shift),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                shift.editedAtMillis?.let {
                    Text(
                        text = editedLabel(it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider()
            StatusUpdateAnswers(shift)
        }
        HorizontalDivider()
        Button(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.status_update_detail_edit))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun StatusUpdateDetailPreview() {
    EmployeeAttendanceTheme {
        StatusUpdateDetailContent(
            shift = StatusUpdateShift(
                clockOutId = "site@1",
                worksiteName = "Main office",
                clockInAtMillis = 1_757_866_920_000,
                clockOutAtMillis = 1_757_896_260_000,
                answers = listOf(
                    AnsweredQuestion(StatusUpdateQuestion.DID_TODAY, "Finished the inventory count"),
                    AnsweredQuestion(StatusUpdateQuestion.COULD_NOT_DO, "Deliveries — van in the shop"),
                ),
                editedAtMillis = null,
            ),
            onEdit = {},
        )
    }
}
