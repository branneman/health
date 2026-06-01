package org.branneman.health

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

@Composable
fun App() {
    MaterialTheme {
        Text("Health")
    }
}

@Preview
@Composable
private fun AppPreview() {
    App()
}
