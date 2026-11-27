package com.movtery.zalithlauncher.trident

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.movtery.zalithlauncher.game.account.Account
import com.movtery.zalithlauncher.game.account.AccountsManager
import com.movtery.zalithlauncher.game.account.isLocalAccount
import com.movtery.zalithlauncher.ui.base.BaseScreen
import com.movtery.zalithlauncher.ui.components.BackgroundCard
import com.movtery.zalithlauncher.ui.screens.NormalNavKey
import com.movtery.zalithlauncher.viewmodel.ScreenBackStackViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val TridentRed = Color(0xFFE53946)
private val OnlineGreen = Color(0xFF34D058)

@Composable
fun TridentScreen(
    backScreenViewModel: ScreenBackStackViewModel
) {
    val account by AccountsManager.currentAccountFlow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var state by remember { mutableStateOf<SyncState?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var needLink by remember { mutableStateOf(false) }
    var online by remember { mutableStateOf<Int?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    fun perform(action: suspend () -> Unit) {
        scope.launch {
            loading = true
            notice = null
            try {
                action()
                error = null
            } catch (e: TridentException) {
                if (e.code == "need_link" || e.code == "device_unknown") needLink = true
                error = e.message
            } catch (e: Exception) {
                error = e.message ?: "Error desconocido"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(account?.uniqueUUID, reload) {
        state = null
        needLink = false
        error = null
        val acc = account
        if (acc == null) {
            error = TridentNet.message("no_account_selected")
            return@LaunchedEffect
        }
        loading = true
        try {
            state = TridentNet.sync(acc)
        } catch (e: TridentException) {
            if (e.code == "need_link" || e.code == "device_unknown") needLink = true
            error = e.message
        } catch (e: Exception) {
            error = e.message
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            TridentNet.pingOnline()?.let { online = it }
            delay(60_000)
        }
    }

    BaseScreen(
        screenKey = NormalNavKey.Trident,
        currentKey = backScreenViewModel.mainScreen.currentKey
    ) { _ ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            BackgroundCard(
                modifier = Modifier
                    .weight(0.34f)
                    .fillMaxHeight()
            ) {
                AccountPanel(
                    account = account,
                    state = state,
                    online = online,
                    needLink = needLink,
                    loading = loading,
                    error = error,
                    notice = notice,
                    onLink = { password, create ->
                        val acc = account
                        if (acc != null) {
                            perform {
                                TridentNet.offlineAuth(acc, password, create)
                                needLink = false
                                reload++
                            }
                        }
                    },
                    onUnlink = {
                        account?.let { TridentNet.unlink(it.username) }
                        reload++
                    },
                    onRefresh = { reload++ }
                )
            }
            BackgroundCard(
                modifier = Modifier
                    .weight(0.36f)
                    .fillMaxHeight()
            ) {
                ShopPanel(
                    wallet = state?.wallet,
                    enabled = !loading && state != null,
                    onBuy = { item ->
                        perform {
                            val w = TridentNet.buy(account, item.id)
                            state = state?.let { it.copy(wallet = w ?: it.wallet) }
                            notice = "Compraste ${item.name}."
                        }
                    },
                    onEquip = { item, on ->
                        perform {
                            val w = TridentNet.equip(account, item.id, on)
                            state = state?.let { it.copy(wallet = w ?: it.wallet) }
                        }
                    }
                )
            }
            BackgroundCard(
                modifier = Modifier
                    .weight(0.30f)
                    .fillMaxHeight()
            ) {
                FriendsPanel(
                    state = state,
                    enabled = !loading && state != null,
                    onAction = { action, name ->
                        perform {
                            TridentNet.friend(account, action, name)
                            state = TridentNet.sync(account)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@Composable
private fun AccountPanel(
    account: Account?,
    state: SyncState?,
    online: Int?,
    needLink: Boolean,
    loading: Boolean,
    error: String?,
    notice: String?,
    onLink: (String, Boolean) -> Unit,
    onUnlink: () -> Unit,
    onRefresh: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    val local = account != null && account.isLocalAccount()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SectionTitle("Trident Net")
        if (online != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(modifier = Modifier.size(8.dp), shape = CircleShape, color = OnlineGreen) {}
                Spacer(Modifier.size(8.dp))
                Text(text = "$online en línea", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text(
            text = account?.username ?: "Sin cuenta",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        val wallet = state?.wallet
        if (wallet != null) {
            Text(text = "${wallet.coins} Trident Coins", color = TridentRed, style = MaterialTheme.typography.titleMedium)
            Text(text = "Los Trident Coins diarios se reclaman dentro del juego.", style = MaterialTheme.typography.bodySmall)
        }
        if (local && needLink) {
            HorizontalDivider()
            Text(
                text = "Cuenta no premium: usá una contraseña de Trident Net para tus monedas, cosméticos y amigos.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = password,
                onValueChange = { password = it.take(64) },
                singleLine = true,
                label = { Text("Contraseña") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !loading && password.length >= 6, onClick = { onLink(password, false) }) {
                    Text("Entrar")
                }
                OutlinedButton(enabled = !loading && password.length >= 6, onClick = { onLink(password, true) }) {
                    Text("Crear cuenta")
                }
            }
        }
        if (local && !needLink && state != null) {
            TextButton(onClick = onUnlink) { Text("Desvincular de Trident Net") }
        }
        if (error != null) {
            Text(text = error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (notice != null) {
            Text(text = notice, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = "Trident Client se instala solo cuando jugás una versión con Fabric.",
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedButton(enabled = !loading, onClick = onRefresh) {
            Text(if (loading) "Cargando…" else "Actualizar")
        }
    }
}

@Composable
private fun ShopPanel(
    wallet: Wallet?,
    enabled: Boolean,
    onBuy: (ShopItem) -> Unit,
    onEquip: (ShopItem, Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        SectionTitle("Cosméticos")
        Spacer(Modifier.height(8.dp))
        if (wallet == null) {
            Text("Conectate a Trident Net para ver la tienda.", style = MaterialTheme.typography.bodySmall)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(wallet.shop, key = { it.id }) { item ->
                    val owned = item.id in wallet.owned
                    val equipped = item.id in wallet.equipped
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = when {
                                    equipped -> "Puesto"
                                    owned -> "Comprado"
                                    else -> "${item.price} coins"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (equipped) OnlineGreen else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        when {
                            !owned -> Button(enabled = enabled && wallet.coins >= item.price, onClick = { onBuy(item) }) {
                                Text("Comprar")
                            }
                            equipped -> OutlinedButton(enabled = enabled, onClick = { onEquip(item, false) }) {
                                Text("Quitar")
                            }
                            else -> Button(enabled = enabled, onClick = { onEquip(item, true) }) {
                                Text("Equipar")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FriendsPanel(
    state: SyncState?,
    enabled: Boolean,
    onAction: (String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SectionTitle("Amigos")
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                modifier = Modifier.weight(1f),
                value = name,
                onValueChange = { v -> name = v.filter { c -> c.isLetterOrDigit() || c == '_' }.take(16) },
                singleLine = true,
                label = { Text("Nombre") }
            )
            Spacer(Modifier.size(8.dp))
            Button(
                enabled = enabled && name.length >= 3,
                onClick = {
                    onAction("request", name)
                    name = ""
                }
            ) { Text("Agregar") }
        }
        if (state != null) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(state.incoming, key = { "in:$it" }) { n ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("$n quiere ser tu amigo", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(enabled = enabled, onClick = { onAction("accept", n) }) { Text("Aceptar") }
                        TextButton(enabled = enabled, onClick = { onAction("decline", n) }) { Text("No") }
                    }
                }
                items(state.friends, key = { "f:${it.name}" }) { f ->
                    val color = when (f.status) {
                        "ingame" -> TridentRed
                        "online" -> OnlineGreen
                        else -> MaterialTheme.colorScheme.outline
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(modifier = Modifier.size(8.dp), shape = CircleShape, color = color) {}
                        Spacer(Modifier.size(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(f.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = when (f.status) {
                                    "ingame" -> "Jugando · ${f.server}"
                                    "online" -> "En línea"
                                    else -> "Desconectado"
                                },
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                items(state.outgoing, key = { "out:$it" }) { n ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Solicitud enviada a $n", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(enabled = enabled, onClick = { onAction("cancel", n) }) { Text("Cancelar") }
                    }
                }
                if (state.friends.isEmpty() && state.incoming.isEmpty() && state.outgoing.isEmpty()) {
                    item { Text("Todavía no tenés amigos en Trident.", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}
