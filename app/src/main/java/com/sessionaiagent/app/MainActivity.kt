package com.sessionaiagent.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sessionaiagent.app.ui.AppScreen
import com.sessionaiagent.app.ui.SaaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SaaTheme {
                val vm: WizardViewModel = viewModel()
                AppScreen(vm)
            }
        }
    }
}
