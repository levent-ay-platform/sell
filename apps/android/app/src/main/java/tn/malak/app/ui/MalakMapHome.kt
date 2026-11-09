package tn.malak.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * French-default, photographic QA preview of the selected M A L A K home.
 * The image can be panned for UX validation, but is NOT a GIS map / 3D SDK.
 * All sample data is fictional and must never receive a verified ownership badge.
 *
 * Map-first UX: a real drag collapses presentation chrome and keeps it hidden
 * until explicit restore/search or tapping a property marker.
 */
@Composable
fun MalakMapHome(
    search: String,
    onSearch: (String) -> Unit,
    selectedCategory: String,
    onCategory: (String) -> Unit,
    favorite: Boolean,
    onFavorite: () -> Unit,
    map3d: Boolean,
    onToggleMap: () -> Unit,
    onOpen: () -> Unit,
    onNavigate: (String) -> Unit,
    notice: String,
    onNotice: (String) -> Unit
) {
    var immersive by rememberSaveable { mutableStateOf(false) }
    var zoom by rememberSaveable { mutableIntStateOf(0) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    val visibleListing = selectedCategory == "Terrains" || selectedCategory == "أراضي" ||
        selectedCategory == "Tous"
    val queryMatches = search.isBlank() || listOf("kelibia", "kélibia", "terrain", "mer", "nabeul")
        .any { search.contains(it, ignoreCase = true) }
    val showListing = visibleListing && queryMatches

    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(0.dp))) {
        // Full-screen hero. This photograph is a non-georeferenced QA layer.
        ScenicBackdrop(
            modifier = Modifier.fillMaxSize().graphicsLayer {
                translationX = panX
                translationY = panY
                scaleX = 1.14f
                scaleY = 1.14f
            },
            previewZoom = zoom + 1
        )

        // Behind all interactive overlays; touch slop avoids dismissing panels on taps.
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { immersive = true },
                    onDrag = { change, amount ->
                        change.consume()
                        panX = (panX + amount.x).coerceIn(-75f, 75f)
                        panY = (panY + amount.y).coerceIn(-110f, 110f)
                    }
                )
            }
        )

        BougainvilleaSway(
            Modifier.align(Alignment.TopEnd).padding(end = 3.dp, top = 1.dp)
                .size(width = 123.dp, height = 80.dp)
        )

        AnimatedVisibility(
            visible = !immersive,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(tween(350)) + slideInVertically(
                initialOffsetY = { -it / 5 }, animationSpec = tween(420)
            ),
            exit = fadeOut(tween(230)) + slideOutVertically(
                targetOffsetY = { -it / 3 }, animationSpec = tween(350)
            )
        ) {
            Column(Modifier.fillMaxWidth()) {
                MapFrenchHeader { onNavigate("visitor") }
                Spacer(Modifier.height(8.dp))
                MapFrenchSearch(search, onSearch) {
                    onNotice("Recherche intelligente : connexion aux biens approuvés à venir.")
                }
                Spacer(Modifier.height(12.dp))
                MapFrenchCategories(selectedCategory, onCategory)
            }
        }

        // Real screen interaction remains available even in immersive map mode.
        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = 10.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            MapCircle("layers") {
                onNotice("Les couches cartographiques seront activées avec la vraie carte.")
            }
            MapCircle("3D") {
                onNotice("Moteur cartographique 3D non intégré dans cette démonstration.")
            }
            MapCircle("+") { zoom = (zoom + 1).coerceAtMost(5); immersive = true }
            MapCircle("−") { zoom = (zoom - 1).coerceAtLeast(0); immersive = true }
        }

        // The marker follows our preview pan. Tapping it reopens the real UI controls.
        Surface(
            modifier = Modifier.align(Alignment.Center).offset(x = (-23).dp, y = 72.dp)
                .graphicsLayer { translationX = panX; translationY = panY }
                .clickable { immersive = false },
            shape = RoundedCornerShape(50), color = Brand.Crimson,
            border = BorderStroke(2.dp, Color.White), shadowElevation = 12.dp
        ) {
            Icon(
                Icons.Outlined.Home, contentDescription = "Voir le terrain de démonstration",
                modifier = Modifier.padding(11.dp).size(24.dp), tint = Color.White
            )
        }

        Surface(
            modifier = Modifier.align(Alignment.CenterStart)
                .padding(start = 11.dp).offset(y = 105.dp),
            color = Color.White.copy(alpha = .85f),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text("Kélibia · vue illustrative",
                Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                color = Brand.Crimson, fontSize = 10.sp)
        }

        AnimatedVisibility(
            visible = !immersive,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(340)) + slideInVertically(
                initialOffsetY = { it / 3 }, animationSpec = tween(410)
            ),
            exit = fadeOut(tween(210)) + slideOutVertically(
                targetOffsetY = { it }, animationSpec = tween(410)
            )
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(bottom = 5.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (showListing) {
                    FrenchPropertyCard(favorite, onFavorite, onOpen)
                }
                FrenchBottomNav(onNavigate)
            }
        }

        AnimatedVisibility(
            visible = immersive,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 10.dp),
            enter = fadeIn(tween(320)), exit = fadeOut(tween(170))
        ) {
            Surface(
                modifier = Modifier.clickable { immersive = false },
                color = Color.White.copy(alpha = .94f),
                shape = RoundedCornerShape(27.dp), shadowElevation = 5.dp
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Search, contentDescription = null,
                        tint = Brand.Crimson, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Rechercher", color = Brand.Crimson, fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold)
                }
            }
        }

        AnimatedVisibility(
            visible = immersive,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp),
            enter = fadeIn(tween(330)) + slideInVertically(
                initialOffsetY = { it / 2 }, animationSpec = tween(410)
            ),
            exit = fadeOut(tween(170))
        ) {
            Surface(
                modifier = Modifier.clickable { immersive = false },
                color = Color.White.copy(alpha = .96f),
                shape = RoundedCornerShape(28.dp),
                border = BorderStroke(1.dp, Color.White),
                shadowElevation = 8.dp
            ) {
                Row(Modifier.padding(horizontal = 24.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Home, contentDescription = null,
                        tint = Brand.Crimson, modifier = Modifier.size(21.dp))
                    Spacer(Modifier.width(9.dp))
                    Text("Voir les biens", fontWeight = FontWeight.Bold,
                        fontSize = 14.sp, color = Brand.Crimson)
                    Text("  ↑", fontSize = 18.sp, color = Brand.Crimson)
                }
            }
        }

        if (notice.isNotBlank()) {
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 185.dp)
                    .padding(horizontal = 20.dp).clickable { onNotice("") },
                color = Brand.Rose, shape = RoundedCornerShape(15.dp),
                shadowElevation = 6.dp
            ) {
                Text(notice + " ×", Modifier.padding(12.dp),
                    color = Brand.Crimson, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun MapFrenchHeader(onAccount: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 9.dp)
            .heightIn(min = 53.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ملاك", fontSize = 29.sp, color = Brand.Crimson,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(7.dp))
            Column {
                Text("Malak", color = Brand.Ink, fontSize = 12.sp,
                    fontWeight = FontWeight.Bold)
                Text("IMMOBILIER · TUNISIE", fontSize = 8.sp,
                    letterSpacing = .3.sp, color = Brand.Crimson)
            }
        }
        Text("Immobilier en Tunisie",
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            maxLines = 1, overflow = TextOverflow.Ellipsis, color = Brand.Ink,
            textAlign = TextAlign.End, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Surface(
            shape = RoundedCornerShape(12.dp), color = Brand.Crimson,
            modifier = Modifier.clickable(onClick = onAccount)
        ) {
            Text("🇹🇳", Modifier.padding(horizontal = 8.dp, vertical = 6.dp), fontSize = 21.sp)
        }
    }
}

@Composable
private fun MapFrenchSearch(value: String, onValue: (String) -> Unit, onAI: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
        color = Color.White.copy(alpha = .95f),
        shape = RoundedCornerShape(29.dp), shadowElevation = 7.dp
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 49.dp).padding(start = 12.dp, end = 5.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Search, contentDescription = "Chercher",
                tint = Brand.Crimson, modifier = Modifier.size(25.dp))
            BasicTextField(
                value = value, onValueChange = onValue, singleLine = true,
                textStyle = TextStyle(fontSize = 12.sp, color = Brand.Ink),
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                decorationBox = { inner ->
                    Box {
                        if (value.isBlank()) {
                            Text("Terrain, villa, maison ou région…",
                                fontSize = 12.sp, color = Brand.Muted,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        inner()
                    }
                }
            )
            Icon(Icons.Outlined.Mic, contentDescription = "Recherche vocale",
                tint = Brand.Crimson,
                modifier = Modifier.size(35.dp).clickable(onClick = onAI).padding(7.dp))
            Surface(
                modifier = Modifier.clickable(onClick = onAI),
                shape = RoundedCornerShape(24.dp), color = Brand.Crimson
            ) {
                Text("✦", Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
                    fontSize = 19.sp, color = Color.White)
            }
        }
    }
}

@Composable
private fun MapFrenchCategories(selected: String, onSelect: (String) -> Unit) {
    val categories = listOf(
        Triple("Terrains", Icons.Outlined.Landscape, "Terrains"),
        Triple("Appartements", Icons.Outlined.Apartment, "Appartements"),
        Triple("Villas", Icons.Outlined.Home, "Villas"),
        Triple("Fermes", Icons.Outlined.Park, "Fermes"),
        Triple("Commerces", Icons.Outlined.Storefront, "Commerces")
    )
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(start = 12.dp, end = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        categories.forEach { (key, icon, label) ->
            val active = selected == key || (selected == "أراضي" && key == "Terrains")
            Surface(
                modifier = Modifier.clickable { onSelect(key) },
                color = if (active) Brand.Crimson else Color.White.copy(alpha = .95f),
                shape = RoundedCornerShape(15.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = .72f)),
                shadowElevation = 2.dp
            ) {
                Column(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Icon(icon, contentDescription = null,
                        tint = if (active) Color.White else Brand.Crimson,
                        modifier = Modifier.size(21.dp))
                    Text(label, fontSize = 10.sp,
                        color = if (active) Color.White else Brand.Ink,
                        fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun FrenchPropertyCard(favorite: Boolean, onFavorite: () -> Unit, onOpen: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xF9FFFAFB), shape = RoundedCornerShape(26.dp),
        border = BorderStroke(1.dp, Color.White),
        shadowElevation = 10.dp
    ) {
        Column(
            Modifier.padding(11.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                Column(Modifier.weight(1f)) {
                    Box(Modifier.fillMaxWidth().height(100.dp)) {
                        CoastalThumbnail(Modifier.fillMaxSize().clip(RoundedCornerShape(13.dp)))
                        Text("Visuel démo", modifier = Modifier.align(Alignment.BottomStart)
                            .padding(5.dp).background(
                                Color.White.copy(alpha = .85f), RoundedCornerShape(8.dp)
                            ).padding(4.dp), fontSize = 9.sp, color = Brand.Crimson)
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(3) { i ->
                            PreviewGalleryThumbnail(i, Modifier.weight(1f).height(27.dp))
                        }
                    }
                }
                Column(Modifier.weight(1.04f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Annonce de démonstration", fontSize = 10.sp,
                        color = Brand.Crimson, fontWeight = FontWeight.Bold)
                    Text("Terrain en bord de mer", fontSize = 15.sp,
                        fontWeight = FontWeight.Bold, lineHeight = 19.sp,
                        maxLines = 2, color = Brand.Ink)
                    Text("⌖ Kélibia, Nabeul", fontSize = 11.sp, color = Brand.Muted)
                    Text("420 000 DT", fontSize = 19.sp,
                        color = Brand.Crimson, fontWeight = FontWeight.Bold)
                }
            }
            HorizontalDivider(color = Color(0xFFECDADD))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                MapFact("Surface", "1 200 m²")
                MapFact("Type", "Terrain")
                MapFact("Vérification", "En attente")
            }
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    modifier = Modifier.size(40.dp).clickable(onClick = onFavorite),
                    color = Color.White, shape = RoundedCornerShape(50),
                    shadowElevation = 2.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(if (favorite) "♥" else "♡",
                            color = Brand.Crimson, fontSize = 24.sp)
                    }
                }
                Surface(
                    modifier = Modifier.weight(1f).clickable(onClick = onOpen),
                    color = Brand.Crimson, shape = RoundedCornerShape(25.dp),
                    shadowElevation = 4.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("Voir les détails  →",
                            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            fontSize = 14.sp, color = Color.White,
                            fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun MapFact(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 10.sp, color = Brand.Muted)
        Text(value, fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp, color = Brand.Ink)
    }
}

@Composable
private fun MapCircle(symbol: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.size(44.dp).clickable(onClick = onClick),
        color = Color.White.copy(alpha = .94f),
        shape = RoundedCornerShape(50), shadowElevation = 6.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            when (symbol) {
                "layers" -> Icon(Icons.Outlined.Layers, contentDescription = "Couches",
                    tint = Brand.Crimson)
                "+" -> Icon(Icons.Outlined.Add, contentDescription = "Zoom avant",
                    tint = Brand.Crimson)
                "−" -> Icon(Icons.Outlined.Remove, contentDescription = "Zoom arrière",
                    tint = Brand.Crimson)
                else -> Text(symbol, fontSize = 14.sp, color = Brand.Crimson,
                    fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun FrenchBottomNav(onNavigate: (String) -> Unit) {
    Surface(
        Modifier.fillMaxWidth(), color = Color.White.copy(alpha = .94f),
        shape = RoundedCornerShape(33.dp), shadowElevation = 7.dp
    ) {
        Row(Modifier.fillMaxWidth().height(62.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly) {
            Row(Modifier.clickable { onNavigate("home") }.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Explore, contentDescription = null,
                    tint = Brand.Crimson, modifier = Modifier.size(25.dp))
                Spacer(Modifier.width(5.dp))
                Text("Explorer", fontSize = 12.sp, color = Brand.Ink,
                    fontWeight = FontWeight.SemiBold)
            }
            Surface(
                modifier = Modifier.size(54.dp).clickable { onNavigate("home") },
                color = Brand.Crimson, shape = RoundedCornerShape(50),
                shadowElevation = 5.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("🇹🇳", fontSize = 26.sp)
                }
            }
            Row(Modifier.clickable { onNavigate("favorites") }.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Place, contentDescription = null,
                    tint = Brand.Crimson, modifier = Modifier.size(25.dp))
                Spacer(Modifier.width(5.dp))
                Text("À proximité", fontSize = 11.sp, color = Brand.Ink,
                    fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
