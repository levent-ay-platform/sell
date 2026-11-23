package tn.malak.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tn.malak.app.R
import tn.malak.app.BuildConfig

private data class PremiumTab(val route: String, val label: Int, val icon: ImageVector)

private val tabs = listOf(
    PremiumTab("home", R.string.v2_ui_map, Icons.Outlined.Map),
    PremiumTab("explore", R.string.v2_ui_explore, Icons.Outlined.Search),
    PremiumTab("seller", R.string.v2_ui_add, Icons.Outlined.AddCircleOutline),
    PremiumTab("alerts", R.string.v2_ui_alerts, Icons.Outlined.NotificationsNone),
    PremiumTab("account", R.string.v2_ui_account, Icons.Outlined.PersonOutline)
)

@Composable
internal fun PremiumBottomBar(route: String, onRoute: (String) -> Unit) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
        shape = RoundedCornerShape(24.dp),
        color = Color.White,
        shadowElevation = 3.dp,
        border = BorderStroke(1.dp, Brand.Border)
    ) {
        Row(
            Modifier.fillMaxWidth().selectableGroup().heightIn(min = 70.dp).padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEach { tab ->
                val selected = route == tab.route
                val primary = tab.route == "seller"
                val action = if (primary) {
                    Modifier.clickable(role = Role.Button) { onRoute(tab.route) }
                } else {
                    Modifier.selectable(selected = selected, role = Role.Tab, onClick = { onRoute(tab.route) })
                }
                Column(
                    Modifier.weight(1f).heightIn(min = 62.dp)
                        .then(action)
                        .padding(horizontal = 2.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Box(
                        Modifier.size(width = 44.dp, height = 32.dp).background(
                            color = when {
                                primary -> Brand.Crimson
                                selected -> Brand.Rose
                                else -> Color.Transparent
                            },
                            shape = CircleShape
                        ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            tab.icon, contentDescription = null, modifier = Modifier.size(22.dp),
                            tint = when {
                                primary -> Color.White
                                selected -> Brand.Crimson
                                else -> Brand.Muted
                            }
                        )
                    }
                    Text(
                        s(tab.label),
                        fontSize = 12.sp, lineHeight = 17.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected || primary) Brand.Ink else Brand.Muted,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
internal fun PremiumPageHeading(title: String, onBack: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (onBack != null) IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, s(R.string.back), tint = Brand.Ink)
        }
        Text(title, modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Brand.Ink)
    }
}

@Composable
private fun PremiumEmpty(
    icon: ImageVector,
    title: String,
    description: String,
    button: String? = null,
    onButton: (() -> Unit)? = null
) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        shape = RoundedCornerShape(24.dp),
        color = Color.White,
        border = BorderStroke(1.dp, Brand.Border)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(shape = CircleShape, color = Brand.NeutralContainer) {
                Icon(
                    icon, contentDescription = null, tint = Brand.Ink,
                    modifier = Modifier.padding(18.dp).size(32.dp)
                )
            }
            Text(
                title, style = MaterialTheme.typography.titleLarge,
                color = Brand.Ink, textAlign = TextAlign.Center
            )
            Text(
                description, style = MaterialTheme.typography.bodyMedium,
                color = Brand.Muted
            )
            if (button != null && onButton != null) Button(
                onClick = onButton,
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Brand.Crimson)
            ) {
                Text(button, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
internal fun PremiumAlertsScreen(onExplore: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Brand.Pearl).verticalScroll(rememberScrollState())) {
        PremiumPageHeading(s(R.string.v2_ui_alerts))
        PremiumEmpty(
            Icons.Outlined.NotificationsNone,
            s(R.string.v2_ui_alerts_empty),
            s(R.string.v2_ui_alerts_explain),
            s(R.string.v2_ui_explore),
            onExplore
        )
        Text(
            s(R.string.v2_ui_alerts_permission),
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
            color = Brand.Muted, fontSize = 13.sp, lineHeight = 20.sp
        )
    }
}

@Composable
internal fun PremiumFavoritesScreen(onExplore: () -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Brand.Pearl).verticalScroll(rememberScrollState())) {
        PremiumPageHeading(s(R.string.favorites_title), onBack)
        PremiumEmpty(
            Icons.Outlined.FavoriteBorder, s(R.string.favorites_empty),
            s(R.string.favorites_hint), s(R.string.v2_ui_explore), onExplore
        )
    }
}

@Composable
private fun PremiumActionRow(icon: ImageVector, title: String, subtitle: String? = null, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        color = Color.White, shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Brand.Border)
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 65.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(shape = RoundedCornerShape(12.dp), color = Brand.NeutralContainer) {
                Icon(icon, null, Modifier.padding(10.dp).size(22.dp), tint = Brand.Ink)
            }
            Column(Modifier.weight(1f)) {
                Text(title, color = Brand.Ink, style = MaterialTheme.typography.titleSmall)
                if (subtitle != null) Text(
                    subtitle, color = Brand.Muted, fontSize = 12.sp, lineHeight = 18.sp
                )
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = Brand.Muted)
        }
    }
}

@Composable
internal fun PremiumAccountScreen(
    phone: String?, onSignIn: () -> Unit, onRequests: () -> Unit, onFavorites: () -> Unit,
    onSettings: () -> Unit, onSeller: () -> Unit, onLogout: (() -> Unit)?
) {
    Column(
        Modifier.fillMaxSize().background(Brand.Pearl)
            .verticalScroll(rememberScrollState()).padding(bottom = 20.dp)
    ) {
        PremiumPageHeading(s(R.string.v2_ui_account))
        Surface(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            shape = RoundedCornerShape(24.dp), color = Color.White,
            border = BorderStroke(1.dp, Brand.Border)
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Surface(shape = CircleShape, color = Brand.NeutralContainer) {
                        Icon(
                            Icons.Outlined.PersonOutline, null, Modifier.padding(13.dp).size(32.dp),
                            tint = Brand.Ink
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (phone.isNullOrBlank()) s(R.string.v2_ui_guest) else s(R.string.v2_ui_member),
                            fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Brand.Ink
                        )
                        Text(
                            phone ?: s(R.string.v2_ui_guest_explain),
                            color = Brand.Muted,
                            style = MaterialTheme.typography.bodySmall.copy(
                                textDirection = if (phone == null) TextDirection.Content else TextDirection.Ltr
                            )
                        )
                    }
                }
                if (phone.isNullOrBlank()) Button(
                    onClick = onSignIn, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Brand.Crimson)
                ) { Text(s(R.string.v2_ui_sign_in)) }
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(s(R.string.v2_ui_my_space), Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Brand.Ink)
            PremiumActionRow(Icons.Outlined.HomeWork,
                s(if (tn.malak.app.BuildConfig.MALAK_DIRECT_PUBLISHING) R.string.direct_my_properties else R.string.v2_my_properties),
                onClick = onRequests)
            PremiumActionRow(Icons.Outlined.FavoriteBorder, s(R.string.favorites), onClick = onFavorites)
            PremiumActionRow(Icons.Outlined.AddCircleOutline, s(R.string.v2_ui_add), onClick = onSeller)
            Spacer(Modifier.height(4.dp))
            PremiumActionRow(Icons.Outlined.Settings, s(R.string.settings), onClick = onSettings)
            if (onLogout != null) PremiumActionRow(
                Icons.Outlined.Logout, s(R.string.v2_ui_sign_out), onClick = onLogout
            )
        }
    }
}

@Composable
internal fun PremiumSettingsScreen(
    language: String, onLanguage: (String) -> Unit, onBack: () -> Unit,
    sound: Boolean, haptic: Boolean, onSound: (Boolean) -> Unit, onHaptic: (Boolean) -> Unit
) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().background(Brand.Pearl).verticalScroll(rememberScrollState())
    ) {
        PremiumPageHeading(s(R.string.settings), onBack)
        Surface(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            color = Color.White, shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, Brand.Border)
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.Language, null, tint = Brand.Crimson)
                    Text(s(R.string.language), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf("ar" to R.string.language_ar, "fr" to R.string.language_fr).forEach { (code, label) ->
                        val selected = language == code
                        Surface(
                            Modifier.weight(1f).clickable { onLanguage(code) }.heightIn(min = 48.dp),
                            color = if (selected) Brand.Rose else Brand.Pearl,
                            border = BorderStroke(1.dp, if (selected) Brand.Crimson else Brand.Border),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Box(Modifier.padding(12.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    s(label), color = if (selected) Brand.Crimson else Brand.Ink,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Surface(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            color = Color.White, shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, Brand.Border)
        ) {
            Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.Shield, null, tint = Brand.Crimson)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(s(R.string.privacy), fontWeight = FontWeight.SemiBold, color = Brand.Ink)
                    Text(s(R.string.privacy_hint), fontSize = 13.sp, color = Brand.Muted)
                    Text(s(R.string.v2_ui_alerts_permission), fontSize = 13.sp, color = Brand.Muted)
                }
            }
        }
        Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            shape = RoundedCornerShape(20.dp), color = Color.White, border = BorderStroke(1.dp, Brand.Border)) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                FeedbackSetting(s(R.string.action_sounds), sound, onSound)
                FeedbackSetting(s(R.string.action_haptics), haptic, onHaptic)
            }
        }
        if (BuildConfig.MALAK_DIRECT_PUBLISHING) {
            val fingerprint = remember(context) { PilotOcrLogin.deviceFingerprint(context) }
            Surface(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                shape = RoundedCornerShape(20.dp), color = Color.White,
                border = BorderStroke(1.dp, Brand.Border)
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(s(R.string.pilot_device_id), color = Brand.Ink, style = MaterialTheme.typography.titleMedium)
                    Text(s(R.string.pilot_device_hint), color = Brand.Muted, style = MaterialTheme.typography.bodySmall)
                    SelectionContainer {
                        Text(fingerprint, color = Brand.Ink, style = MaterialTheme.typography.bodySmall.copy(
                            textDirection = TextDirection.Ltr))
                    }
                }
            }
        }
    }
}

@Composable
private fun FeedbackSetting(label: String, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = enabled, role = Role.Switch, onValueChange = change)
        .heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = Brand.Ink)
        Switch(checked = enabled, onCheckedChange = null)
    }
}
