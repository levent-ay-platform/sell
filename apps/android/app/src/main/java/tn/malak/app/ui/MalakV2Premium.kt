package tn.malak.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tn.malak.app.R

/**
 * Map-first V2 design primitives.
 * Keep the original crimson / pearl source of truth; do not mix the legacy teal hero here.
 * All buttons route to a real destination. Unimplemented backend features are labeled as such.
 */
internal object PremiumColors {
    val Rose = Color(0xFFFBE9EE)
    val Pearl = Color(0xFFFFFDFB)
    val Border = Color(0xFFE8E1DF)
    val Ink = Color(0xFF22242B)
    val Muted = Brand.Muted
}

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
        color = PremiumColors.Pearl,
        shadowElevation = 7.dp,
        border = BorderStroke(1.dp, PremiumColors.Border)
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 70.dp).padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEach { tab ->
                val selected = route == tab.route
                val primary = tab.route == "seller"
                Column(
                    Modifier.weight(1f).heightIn(min = 62.dp)
                        .clickable(onClickLabel = s(tab.label)) { onRoute(tab.route) }
                        .padding(horizontal = 2.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    if (primary) {
                        Surface(shape = CircleShape, color = Brand.Crimson) {
                            Icon(
                                tab.icon, contentDescription = s(tab.label),
                                modifier = Modifier.padding(7.dp).size(24.dp),
                                tint = Color.White
                            )
                        }
                    } else {
                        Icon(
                            tab.icon, contentDescription = s(tab.label),
                            modifier = Modifier.size(24.dp),
                            tint = if (selected) Brand.Crimson else PremiumColors.Muted
                        )
                    }
                    Text(
                        s(tab.label),
                        fontSize = 12.sp, lineHeight = 17.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected || primary) Brand.Crimson else PremiumColors.Muted,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
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
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, s(R.string.back), tint = PremiumColors.Ink)
        }
        Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = PremiumColors.Ink)
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
        border = BorderStroke(1.dp, PremiumColors.Border)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(shape = CircleShape, color = PremiumColors.Rose) {
                Icon(
                    icon, contentDescription = null, tint = Brand.Crimson,
                    modifier = Modifier.padding(18.dp).size(32.dp)
                )
            }
            Text(
                title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                color = PremiumColors.Ink
            )
            Text(
                description, fontSize = 14.sp, lineHeight = 21.sp,
                color = PremiumColors.Muted
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
    Column(Modifier.fillMaxSize().background(PremiumColors.Pearl)) {
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
            color = PremiumColors.Muted, fontSize = 13.sp, lineHeight = 20.sp
        )
    }
}

@Composable
internal fun PremiumFavoritesScreen(onExplore: () -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(PremiumColors.Pearl)) {
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
        Modifier.fillMaxWidth().clickable(onClickLabel = title, onClick = onClick),
        color = Color.White, shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, PremiumColors.Border)
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 65.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(shape = RoundedCornerShape(12.dp), color = PremiumColors.Rose) {
                Icon(icon, null, Modifier.padding(10.dp).size(22.dp), tint = Brand.Crimson)
            }
            Column(Modifier.weight(1f)) {
                Text(title, color = PremiumColors.Ink, fontWeight = FontWeight.Medium)
                if (subtitle != null) Text(
                    subtitle, color = PremiumColors.Muted, fontSize = 12.sp, lineHeight = 18.sp
                )
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = PremiumColors.Muted)
        }
    }
}

@Composable
internal fun PremiumAccountScreen(
    phone: String?, onSignIn: () -> Unit, onRequests: () -> Unit, onFavorites: () -> Unit,
    onSettings: () -> Unit, onSeller: () -> Unit, onLogout: (() -> Unit)?
) {
    Column(
        Modifier.fillMaxSize().background(PremiumColors.Pearl)
            .verticalScroll(rememberScrollState()).padding(bottom = 20.dp)
    ) {
        PremiumPageHeading(s(R.string.v2_ui_account))
        Surface(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            shape = RoundedCornerShape(24.dp), color = Color.White,
            border = BorderStroke(1.dp, PremiumColors.Border)
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Surface(shape = CircleShape, color = PremiumColors.Rose) {
                        Icon(
                            Icons.Outlined.PersonOutline, null, Modifier.padding(13.dp).size(32.dp),
                            tint = Brand.Crimson
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (phone.isNullOrBlank()) s(R.string.v2_ui_guest) else s(R.string.v2_ui_member),
                            fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = PremiumColors.Ink
                        )
                        Text(
                            phone ?: s(R.string.v2_ui_guest_explain),
                            color = PremiumColors.Muted, fontSize = 13.sp, lineHeight = 20.sp
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
            Text(s(R.string.v2_ui_my_space), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = PremiumColors.Ink)
            PremiumActionRow(Icons.Outlined.HomeWork, s(R.string.v2_my_properties), onClick = onRequests)
            PremiumActionRow(Icons.Outlined.FavoriteBorder, s(R.string.favorites), onClick = onFavorites)
            PremiumActionRow(Icons.Outlined.AddCircleOutline, s(R.string.v2_ui_add), onClick = onSeller)
            Spacer(Modifier.height(4.dp))
            Text(s(R.string.settings), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = PremiumColors.Ink)
            PremiumActionRow(Icons.Outlined.Settings, s(R.string.settings), onClick = onSettings)
            if (onLogout != null) PremiumActionRow(
                Icons.Outlined.Logout, s(R.string.v2_ui_sign_out), onClick = onLogout
            )
        }
    }
}

@Composable
internal fun PremiumSettingsScreen(
    language: String, onLanguage: (String) -> Unit, onBack: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().background(PremiumColors.Pearl).verticalScroll(rememberScrollState())
    ) {
        PremiumPageHeading(s(R.string.settings), onBack)
        Surface(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            color = Color.White, shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, PremiumColors.Border)
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
                            color = if (selected) PremiumColors.Rose else PremiumColors.Pearl,
                            border = BorderStroke(1.dp, if (selected) Brand.Crimson else PremiumColors.Border),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Box(Modifier.padding(12.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    s(label), color = if (selected) Brand.Crimson else PremiumColors.Ink,
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
            border = BorderStroke(1.dp, PremiumColors.Border)
        ) {
            Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.Shield, null, tint = Brand.Crimson)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(s(R.string.privacy), fontWeight = FontWeight.SemiBold, color = PremiumColors.Ink)
                    Text(s(R.string.privacy_hint), fontSize = 13.sp, color = PremiumColors.Muted)
                    Text(s(R.string.v2_ui_alerts_permission), fontSize = 13.sp, color = PremiumColors.Muted)
                }
            }
        }
    }
}
