package com.local.mp3box.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalContext
import com.local.mp3box.Mp3BoxApp
import com.local.mp3box.di.AppContainer

/** The app's dependency graph, for screens that build their own ViewModels. */
@Composable
@ReadOnlyComposable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as Mp3BoxApp).container
