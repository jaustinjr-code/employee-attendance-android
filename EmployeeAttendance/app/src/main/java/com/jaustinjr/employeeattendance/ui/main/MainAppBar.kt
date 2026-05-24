package com.jaustinjr.employeeattendance.ui.main

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import com.jaustinjr.employeeattendance.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppBar(title: String) {
    TopAppBar(title = {
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
    },
        navigationIcon = {
            IconButton(onClick = {}) {
                Icon(
                    painter = painterResource(R.drawable.account_circle_24px),
                    contentDescription = null,
                )
            }
        },
        actions = {
            IconButton(onClick = {}) {
                Icon(
                    painter = painterResource(R.drawable.settings_24px),
                    contentDescription = null,
                )
            }
        })
}