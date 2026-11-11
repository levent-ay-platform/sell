package tn.malak.app.ui

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tn.malak.app.R
import java.util.Locale

private val Sea = Color(0xFF087C9A)
private val SeaLight = Color(0xFFE9F8F9)
private val Border = Color(0xFFE8E8EB)

/**
 * Localized strings must NOT replace LocalContext.
 * The original Activity Context is needed by ActivityResultRegistryOwner when
 * SellerOcrJourney registers its Android document picker.
 */
private val LocalMalakStringsContext = staticCompositionLocalOf<Context> {
    error("Localized Malak resources are missing")
}

/** All consumer text is sourced from localized Android resources. */
@Composable
internal fun s(id: Int): String = LocalMalakStringsContext.current.getString(id)

/** Arabic RTL, French LTR; an explicit user language selection drives every Compose layout. */
@Composable
internal fun MalakApp(
    ownerEvidenceSession: OwnerEvidenceSession? = null,
    authenticatedOwnerSession: AuthenticatedOwnerSession? = null
) {
    val baseContext = LocalContext.current
    val preferences = remember(baseContext) {
        baseContext.getSharedPreferences("malak_public_preferences", Context.MODE_PRIVATE)
    }
    var language by rememberSaveable {
        mutableStateOf(preferences.getString("language", "fr")?.takeIf { it in setOf("ar", "fr") } ?: "fr")
    }
    val localizedContext = remember(baseContext, language) {
        val configuration = Configuration(baseContext.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(if (language == "ar") "ar" else "fr"))
        baseContext.createConfigurationContext(configuration)
    }
    val direction = if (language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr
    var screen by rememberSaveable { mutableStateOf("home") }
    var category by rememberSaveable { mutableStateOf("all") }
    var query by rememberSaveable { mutableStateOf("") }
    // In-memory only: never promote fixed pilot OTP to an authenticated server identity.
    var pilotPhone by remember { mutableStateOf<String?>(null) }
    val tabs = setOf("home", "map", "favorites", "more")

    CompositionLocalProvider(LocalMalakStringsContext provides localizedContext,
        LocalLayoutDirection provides direction,
        LocalOwnerEvidenceSession provides (if (PilotOtpGate.enabled) null else ownerEvidenceSession),
        LocalAuthenticatedOwnerSession provides (if (PilotOtpGate.enabled) null else authenticatedOwnerSession)) {
        MaterialTheme(colorScheme = lightColorScheme(
            primary = Brand.Crimson,
            background = Brand.Pearl,
            surface = Brand.Pearl,
            onSurface = Brand.Ink
        )) {
            Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), color = Brand.Pearl) {
                if (PilotOtpGate.enabled && pilotPhone == null) {
                    PilotOtpScreen(onEnterPilot = { pilotPhone = it; screen = "home" })
                } else Scaffold(
                    containerColor = Brand.Pearl,
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    topBar = {
                        if (PilotOtpGate.enabled) PilotSessionBar(pilotPhone.orEmpty()) {
                            pilotPhone = null
                            screen = "home"
                        }
                    },
                    bottomBar = {
                        if (screen in tabs) MalakBottomBar(screen) { screen = it }
                    }
                ) { insets ->
                    Box(Modifier.fillMaxSize().padding(insets)) {
                        when (screen) {
                            "home" -> MalakHome(query, { query = it }, category, { category = it }, { screen = "seller" })
                            "map" -> MalakEmptyPage(R.string.map_title, R.string.map_unavailable,
                                R.string.map_unavailable_hint, Icons.Outlined.Map, { screen = "home" })
                            "favorites" -> MalakEmptyPage(R.string.favorites_title, R.string.favorites_empty,
                                R.string.favorites_hint, Icons.Outlined.FavoriteBorder, { screen = "home" })
                            "more" -> MalakMorePage(language, onLanguage = {
                                language = it
                                preferences.edit().putString("language", it).apply()
                            }, onSeller = { screen = "seller" })
                            "seller" -> SellerOcrJourney(onBack = { screen = "home" })
                            else -> LaunchedEffect(Unit) { screen = "home" }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MalakHome(
    query: String, onQuery: (String) -> Unit,
    selected: String, onSelected: (String) -> Unit, onSeller: () -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 18.dp)) {
        Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Sea, Color(0xFF35ABC1), SeaLight)))) {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 23.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(17.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Surface(shape = RoundedCornerShape(18.dp), color = Color.White.copy(alpha = .96f)) {
                        Icon(Icons.Outlined.Home, null, Modifier.padding(10.dp).size(30.dp), tint = Brand.Crimson)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("MALAK / ملاك", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                        Text(s(R.string.brand_subtitle), color = Color.White.copy(alpha = .93f),
                            fontSize = 12.sp, maxLines = 2)
                    }
                }
                // Primary seller action stays visible without scrolling, including small screens.
                Button(onClick = onSeller, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White, contentColor = Brand.Crimson)) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(s(R.string.add_land), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color.White)
                    .heightIn(min = 58.dp).padding(horizontal = 15.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.Search, s(R.string.search_hint), tint = Brand.Crimson,
                        modifier = Modifier.size(24.dp))
                    BasicTextField(
                        query, onValueChange = { onQuery(it.take(100)) }, singleLine = true,
                        textStyle = TextStyle(color = Brand.Ink, fontSize = 15.sp,
                            textAlign = TextAlign.Start), modifier = Modifier.weight(1f),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (query.isEmpty()) Text(s(R.string.search_hint), color = Brand.Muted,
                                    fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                inner()
                            }
                        }
                    )
                    if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.Close, s(R.string.clear), tint = Brand.Ink)
                    }
                }
                Text(s(R.string.find_property), fontSize = 24.sp, fontWeight = FontWeight.Bold,
                    color = Color.White, modifier = Modifier.padding(top = 6.dp))
                Text(s(R.string.home_hero_subtitle), fontSize = 13.sp, color = Color.White.copy(alpha = .96f),
                    lineHeight = 21.sp)
            }
        }
        CategoryPills(selected, onSelected)
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val emptyTitle = if (query.isBlank() && selected == "all") R.string.listings_empty_title else R.string.search_empty_title
            val emptyHint = if (query.isBlank() && selected == "all") R.string.listings_empty_hint else R.string.search_empty_hint
            PolishedEmptyState(Icons.Outlined.RealEstateAgent, s(emptyTitle), s(emptyHint))
        }
    }
}

private data class PropertyCategory(val key: String, val label: Int, val icon: ImageVector)
private val Categories = listOf(
    PropertyCategory("all", R.string.all, Icons.Outlined.GridView),
    PropertyCategory("terrains", R.string.terrains, Icons.Outlined.Landscape),
    PropertyCategory("villas", R.string.villas, Icons.Outlined.House),
    PropertyCategory("apartments", R.string.apartments, Icons.Outlined.Apartment),
    PropertyCategory("farms", R.string.farms, Icons.Outlined.Agriculture),
    PropertyCategory("commercial", R.string.commercial, Icons.Outlined.Storefront)
)

@Composable
private fun CategoryPills(selected: String, onSelected: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Categories.forEach { category ->
            val active = selected == category.key
            Surface(shape = RoundedCornerShape(18.dp), shadowElevation = if (active) 2.dp else 0.dp,
                color = if (active) Brand.Crimson else Color.White,
                border = if (active) null else BorderStroke(1.dp, Border),
                modifier = Modifier.clickable { onSelected(category.key) }) {
                Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 13.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(category.icon, null, Modifier.size(20.dp), tint = if (active) Color.White else Brand.Crimson)
                    Text(s(category.label), fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        color = if (active) Color.White else Brand.Ink)
                }
            }
        }
    }
}

@Composable
private fun PolishedEmptyState(icon: ImageVector, title: String, subtitle: String) {
    Surface(shape = RoundedCornerShape(24.dp), color = Color.White, border = BorderStroke(1.dp, Border),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 25.dp, vertical = 35.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Surface(shape = CircleShape, color = SeaLight) {
                Icon(icon, null, Modifier.padding(17.dp).size(35.dp), tint = Sea)
            }
            Text(title, color = Brand.Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center)
            Text(subtitle, color = Brand.Muted, fontSize = 13.sp, textAlign = TextAlign.Center,
                lineHeight = 21.sp)
        }
    }
}

@Composable
private fun MalakEmptyPage(title: Int, headline: Int, detail: Int, icon: ImageVector, onHome: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(19.dp)) {
        Text(s(title), color = Brand.Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        PolishedEmptyState(icon, s(headline), s(detail))
        OutlinedButton(onClick = onHome, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text(s(R.string.home))
        }
    }
}

@Composable
private fun MalakMorePage(language: String, onLanguage: (String) -> Unit, onSeller: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(s(R.string.more_title), fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Brand.Ink)
        Surface(color = Color.White, shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, Border)) {
            Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.Language, null, tint = Brand.Crimson)
                    Text(s(R.string.language), color = Brand.Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf("ar" to R.string.language_ar, "fr" to R.string.language_fr).forEach { (code, title) ->
                        val active = language == code
                        Surface(modifier = Modifier.weight(1f).clickable { onLanguage(code) },
                            shape = RoundedCornerShape(14.dp),
                            color = if (active) Brand.Rose else Color(0xFFF6F7F8),
                            border = BorderStroke(1.dp, if (active) Brand.Crimson else Border)) {
                            Box(Modifier.heightIn(min = 48.dp).padding(10.dp), contentAlignment = Alignment.Center) {
                                Text(s(title), color = Brand.Ink, fontWeight = if(active) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }
                }
            }
        }
        Surface(color = Color.White, shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, Border)) {
            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.Shield, null, tint = Sea, modifier = Modifier.size(28.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(s(R.string.privacy), color = Brand.Ink, fontWeight = FontWeight.SemiBold)
                    Text(s(R.string.privacy_hint), color = Brand.Muted, fontSize = 12.sp)
                }
            }
        }
        Button(onClick = onSeller, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            shape = RoundedCornerShape(17.dp), colors = ButtonDefaults.buttonColors(containerColor = Brand.Crimson)) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(s(R.string.add_land))
        }
        Text(s(R.string.account_unavailable), color = Brand.Muted, fontSize = 12.sp)
    }
}

private data class NavigationTab(val route: String, val title: Int, val icon: ImageVector)
private val NavTabs = listOf(
    NavigationTab("home", R.string.home, Icons.Outlined.Home),
    NavigationTab("map", R.string.map, Icons.Outlined.Map),
    NavigationTab("favorites", R.string.favorites, Icons.Outlined.FavoriteBorder),
    NavigationTab("more", R.string.more, Icons.Outlined.MoreHoriz)
)

@Composable
private fun MalakBottomBar(active: String, onNavigate: (String) -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        color = Color.White, shape = RoundedCornerShape(26.dp), shadowElevation = 7.dp) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            NavTabs.forEach { tab ->
                val selected = active == tab.route
                Column(Modifier.weight(1f).clip(RoundedCornerShape(15.dp)).clickable { onNavigate(tab.route) }
                    .heightIn(min = 58.dp).padding(horizontal = 2.dp, vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Icon(tab.icon, contentDescription = s(tab.title), modifier = Modifier.size(25.dp),
                        tint = if(selected) Brand.Crimson else Brand.Muted)
                    Text(s(tab.title), fontSize = 11.sp, color = if(selected) Brand.Crimson else Brand.Muted,
                        fontWeight = if(selected) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
