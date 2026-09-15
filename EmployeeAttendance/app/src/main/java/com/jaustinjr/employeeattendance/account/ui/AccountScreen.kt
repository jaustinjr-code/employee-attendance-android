package com.jaustinjr.employeeattendance.account.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jaustinjr.employeeattendance.EmployeeAttendanceApplication
import com.jaustinjr.employeeattendance.settings.UserProfileStore
import com.jaustinjr.employeeattendance.statusupdate.history.StatusUpdateDay
import com.jaustinjr.employeeattendance.statusupdate.history.StatusUpdateHistoryViewModel
import com.jaustinjr.employeeattendance.statusupdate.history.ui.statusUpdateHistorySection
import com.jaustinjr.employeeattendance.ui.theme.EmployeeAttendanceTheme
import kotlinx.coroutines.flow.StateFlow

/** Backs the account screen's display name section. */
class AccountViewModel(private val userProfileStore: UserProfileStore) : ViewModel() {

    /** The name shown in the attendance greeting; empty when the user hasn't set one. */
    val displayName: StateFlow<String> = userProfileStore.displayName

    fun onDisplayNameChanged(name: String) {
        userProfileStore.setDisplayName(name)
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as EmployeeAttendanceApplication).container
                AccountViewModel(container.userProfileStore)
            }
        }
    }
}

/**
 * The account screen: the user's display name, then their past status updates. Opened from the
 * account button in the app bar.
 */
@Composable
fun AccountScreen(
    onOpenStatusUpdate: (clockOutId: String) -> Unit,
    modifier: Modifier = Modifier,
    accountViewModel: AccountViewModel = viewModel(factory = AccountViewModel.Factory),
    historyViewModel: StatusUpdateHistoryViewModel =
        viewModel(factory = StatusUpdateHistoryViewModel.Factory),
) {
    val displayName by accountViewModel.displayName.collectAsStateWithLifecycle()
    val historyDays by historyViewModel.days.collectAsStateWithLifecycle()
    AccountContent(
        displayName = displayName,
        onDisplayNameChanged = accountViewModel::onDisplayNameChanged,
        historyDays = historyDays,
        onOpenStatusUpdate = onOpenStatusUpdate,
        modifier = modifier,
    )
}

/**
 * Stateless account page. It is a list of independent sections, each a [LazyListScope] extension
 * that owns its own layout and keys and takes only its own state: to reorder the page, or move a
 * section onto another screen, move its call.
 */
@Composable
fun AccountContent(
    displayName: String,
    onDisplayNameChanged: (String) -> Unit,
    historyDays: List<StatusUpdateDay>,
    onOpenStatusUpdate: (clockOutId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .navigationBarsPadding(),
        contentPadding = PaddingValues(20.dp),
    ) {
        displayNameSection(displayName = displayName, onDisplayNameChanged = onDisplayNameChanged)
        accountSectionDivider(key = "divider_name_history")
        statusUpdateHistorySection(days = historyDays, onOpenShift = onOpenStatusUpdate)
    }
}

/** A divider between two account sections. [key] must be unique on the page. */
fun LazyListScope.accountSectionDivider(key: String) {
    item(key = key) {
        HorizontalDivider(Modifier.padding(vertical = 16.dp))
    }
}

@Preview(showBackground = true)
@Composable
private fun AccountPreview() {
    EmployeeAttendanceTheme {
        AccountContent(
            displayName = "Jordan",
            onDisplayNameChanged = {},
            historyDays = emptyList(),
            onOpenStatusUpdate = {},
        )
    }
}
