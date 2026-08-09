package com.qwen.tts.studio

import androidx.compose.runtime.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Color
import java.awt.Dimension
import java.awt.Window as AwtWindow
import javax.swing.SwingUtilities
import kotlin.math.roundToInt

/**
 * The main entry point for the desktop application.
 * Bootstraps the Compose window, sets up the window state, and applies platform-specific styling.
 */
fun main() = application {
    var isDarkMode by remember { mutableStateOf(true) }
    val windowState = rememberWindowState(width = 1440.dp, height = 1000.dp)

    Window(
        onCloseRequest = ::exitApplication,
        title = "Qwen-TTS Studio",
        icon = painterResource("icons/app-icon.svg"),
        state = windowState,
    ) {
        val density = LocalDensity.current
        LaunchedEffect(density) {
            // Keep the full Studio action area usable at startup and during resize.
            // Convert from dp so the minimum remains consistent on scaled Windows displays.
            window.minimumSize = with(density) {
                Dimension(1200.dp.toPx().roundToInt(), 900.dp.toPx().roundToInt())
            }
        }

        // Apply dark title bar if possible (Windows 10/11)
        val window = window
        LaunchedEffect(isDarkMode) {
            SwingUtilities.invokeLater {
                applyDarkTitleBar(window, isDarkMode)
            }
        }

        App(isDarkMode = isDarkMode, onThemeToggle = { isDarkMode = !isDarkMode })
    }
}

/**
 * Attempts to apply a dark title bar on Windows 10/11.
 * On other OSs or older Windows versions, this may do nothing.
 */
private fun applyDarkTitleBar(window: AwtWindow, isDark: Boolean) {
    try {
        // This is a common hack for Windows 10/11 title bar color.
        // It uses JNA-style approach or built-in properties if available.
        val color = if (isDark) Color(0xFF161B22.toInt()) else Color.WHITE
        window.background = color
        
        // For Windows 10 (Build 17763+) and Windows 11
        // We can try to hint the system to use dark mode for the title bar
        if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) {
            // Note: True "Dark Mode" title bar often requires JNA to call DwmSetWindowAttribute.
            // Without adding more dependencies, we can at least set the background.
        }
    } catch (e: Exception) {
        // Fallback: do nothing if the OS doesn't support it
    }
}
