package com.skz.localdrop.ui.screens

import androidx.compose.runtime.Composable
import com.skz.localdrop.core.AppState
import com.skz.localdrop.core.Device
import com.skz.localdrop.core.PairDialog

@Composable
fun DeviceSheet(d: Device?, s: AppState, onClose: () -> Unit) {}

@Composable
fun PairSheet(p: PairDialog?) {}

@Composable
fun QrScanner(onClose: () -> Unit) {}

@Composable
fun Onboarding(s: AppState) {}
