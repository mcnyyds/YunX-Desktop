package com.yunx.app.service

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yunx.app.ui.GlobalSnackbarHost
import com.yunx.app.ui.components.OverlayDialogHost
import com.yunx.app.ui.jcef.JcefHolder
import com.yunx.app.ui.login.*
import com.yunx.app.ui.theme.ComposeEmptyActivityTheme
import com.yunx.app.ui.viewmodel.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun LoginWindow(task: LoginTask, tasks: LoginTasks, accounts: ServiceAccounts) {
    val scope = rememberCoroutineScope()
    var prepared by remember(task.id) { mutableStateOf(false) }
    val owner = remember(task.id) { object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() } }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    val cancel = { tasks.finish(task.id, "cancelled"); Unit }
    val saved: () -> Unit = {
        scope.launch {
            try {
                val account = accounts.directory.list(task.platform).maxByOrNull { it.updatedAt }
                    ?: error("Login completed without an account")
                tasks.finish(task.id, "succeeded", ServiceAccounts.accountJson(task.platform, account))
            } catch (error: Exception) {
                tasks.finish(task.id, "failed", message = error.message.orEmpty())
            }
        }
    }
    Window(onCloseRequest = cancel, title = "YunX - ${task.platform} 登录",
        state = rememberWindowState(size = DpSize(960.dp, 720.dp))) {
        LaunchedEffect(task.id) {
            try {
                if (task.platform in setOf("quark", "uc", "xunlei", "baidu", "c139", "pan115")) {
                    if (task.newAccount) {
                        withContext(Dispatchers.IO) { JcefHolder.initBlocking() }
                        MultiAccountViewModel(accounts.directory).prepareAddAccount(task.platform)
                    } else {
                        JcefHolder.initInBackground()
                    }
                }
                prepared = true
            } catch (error: Exception) {
                tasks.finish(task.id, "failed", message = error.message.orEmpty())
            }
        }
        CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
            ComposeEmptyActivityTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize()) {
                        if (prepared) LoginContent(task, accounts, cancel, saved)
                        else CircularProgressIndicator(Modifier.align(Alignment.Center))
                        OverlayDialogHost()
                        GlobalSnackbarHost()
                    }
                }
            }
        }
    }
}

@Composable
private fun LoginContent(task: LoginTask, accounts: ServiceAccounts, cancel: () -> Unit, saved: () -> Unit) {
    when (task.platform) {
        "quark" -> {
            val vm: QuarkAccountViewModel = viewModel(factory = QuarkAccountViewModel.Factory(accounts.quark))
            remember(vm) { if (task.newAccount) vm.beginAddAccount(); true }
            QuarkLoginScreen(vm, cancel, saved)
        }
        "uc" -> {
            val vm: UCAccountViewModel = viewModel(factory = UCAccountViewModel.Factory(accounts.uc))
            remember(vm) { if (task.newAccount) vm.beginAddAccount(); true }
            UCLoginScreen(vm, cancel, saved)
        }
        "xunlei" -> {
            val vm: XunleiAccountViewModel = viewModel(factory = XunleiAccountViewModel.Factory(accounts.xunlei))
            remember(vm) { if (task.newAccount) vm.beginAddAccount(); true }
            XunleiWebLoginScreen(vm, cancel, saved)
        }
        "baidu" -> {
            val vm: BaiduAccountViewModel = viewModel(factory = BaiduAccountViewModel.Factory(accounts.baidu))
            remember(vm) { if (task.newAccount) vm.beginAddAccount(); true }
            BaiduLoginScreen(vm, cancel, saved)
        }
        "c139" -> {
            val vm: C139AccountViewModel = viewModel(factory = C139AccountViewModel.Factory(accounts.c139))
            remember(vm) { if (task.newAccount) vm.beginAddAccount(); true }
            C139LoginScreen(vm, cancel, saved)
        }
        "pan115" -> {
            val vm: Pan115AccountViewModel = viewModel(factory = Pan115AccountViewModel.Factory(accounts.pan115))
            remember(vm) { if (task.newAccount) vm.beginAddAccount(); true }
            Pan115LoginScreen(vm, cancel, saved)
        }
        "pan123" -> {
            val vm: Pan123AccountViewModel = viewModel(factory = Pan123AccountViewModel.Factory(accounts.pan123))
            remember(vm) { if (task.newAccount) vm.beginAddAccount(); true }
            Pan123LoginScreen(vm, cancel, saved)
        }
        "guangya" -> {
            val vm: GuangYaAccountViewModel = viewModel(factory = GuangYaAccountViewModel.Factory(accounts.guangya))
            remember(vm) { if (task.newAccount) vm.beginAddAccount(); true }
            GuangYaLoginScreen(vm, cancel, saved)
        }
        "ilanzou" -> {
            val vm: ILanzouAccountViewModel = viewModel(factory = ILanzouAccountViewModel.Factory(accounts.ilanzou))
            remember(vm) { if (task.newAccount) vm.beginAddAccount(); true }
            ILanzouLoginScreen(vm, cancel, saved)
        }
        "lanzou" -> {
            val vm: LanzouAccountViewModel = viewModel(factory = LanzouAccountViewModel.Factory(accounts.lanzou))
            remember(vm) { if (task.newAccount) vm.beginAddAccount(); true }
            LanzouLoginScreen(vm, cancel, saved)
        }
    }
}
