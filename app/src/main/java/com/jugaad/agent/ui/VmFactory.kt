package com.jugaad.agent.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.jugaad.agent.JugaadApp
import com.jugaad.agent.di.ServiceLocator
import android.content.Context

/** Minimal ViewModel factory so screens can do `viewModel(factory = vmFactory { MyVm(...) })`. */
inline fun <VM : ViewModel> vmFactory(crossinline create: () -> VM): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
    }

fun Context.services(): ServiceLocator =
    (applicationContext as JugaadApp).services
