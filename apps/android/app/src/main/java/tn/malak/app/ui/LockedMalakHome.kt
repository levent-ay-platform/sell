package tn.malak.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tn.malak.app.R

/**
 * MALAK_HOME_STRICT_LOCK_2026_11_09
 *
 * Actual interactive Compose controls over a photographic preview.
 * The map/photo and all displayed listings are synthetic and are NOT real cadastral
 * maps or ownership verification. See docs/design/LOCKED-HOME-2026-11-09.md.
 * On the real map SDK milestone, replace only the photographic/map layer and its
 * pan/zoom signals, without changing this approved UI hierarchy.
 */
@Composable
fun LockedMalakHome(
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
    val red = Color(0xFFF20A25)
    val coral = Color(0xFFE94B44)
    val activeTerrain = selectedCategory == "Terrains" || selectedCategory == "أراضي"
    val matches = search.isBlank() || listOf("terrain", "hammamet", "mer", "villa")
        .any { search.contains(it, ignoreCase = true) }
    val showCard = activeTerrain && matches

    Box(Modifier.fillMaxSize().background(Color(0xFF0D69B8))) {
        // Stable image-based V1 map scene. No fake geospatial 3D or server data.
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxSize()
                    .background(Brush.verticalGradient(
                        0f to Color(0xFF0064AF),
                        .27f to Color(0xFF4DA9DA),
                        .44f to Color(0xFFFDD3A9),
                        1f to Color(0xFFB8DBE5)
                    ))
            )
            Image(
                painter = painterResource(R.drawable.malak_coast_preview),
                contentDescription = "Aperçu photographique de côte, pas une carte géographique",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().fillMaxHeight(.73f)
                    .align(Alignment.BottomCenter)
                    .graphicsLayer {
                        scaleX = 1.14f + zoom * .06f
                        scaleY = 1.14f + zoom * .06f
                        translationX = panX
                        translationY = panY
                    }
            )
            // Gesture only on the image layer, while controls on top remain clickable.
            Box(Modifier.fillMaxSize().pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { immersive = true },
                    onDrag = { change, drag ->
                        change.consume()
                        panX = (panX + drag.x).coerceIn(-82f, 82f)
                        panY = (panY + drag.y).coerceIn(-92f, 92f)
                    }
                )
            })
        }

        // Photographic flower decoration comes from the existing approved media
        // set; it must not intercept map taps.
        BougainvilleaSway(
            Modifier.align(Alignment.TopStart).size(width = 150.dp, height = 60.dp)
        )

        AnimatedVisibility(
            visible = !immersive,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(tween(300)) + slideInVertically(tween(350)) { -it / 5 },
            exit = fadeOut(tween(220)) + slideOutVertically(tween(300)) { -it / 4 }
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Row(
                    Modifier.fillMaxWidth().height(77.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Home, null, tint = red,
                            modifier = Modifier.size(42.dp))
                        Spacer(Modifier.width(6.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text("Malak / ملاك", fontSize = 24.sp, color = Color.White,
                                fontWeight = FontWeight.Bold, maxLines = 1)
                            Text("I M M O B I L I E R   E N   T U N I S I E",
                                fontSize = 7.sp, color = Color.White, maxLines = 1)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        LockedRoundIcon(Icons.Outlined.Notifications, "Notifications", 40) {
                            onNotice("Notifications disponibles après connexion")
                        }
                        LockedRoundIcon(Icons.Outlined.PersonOutline, "Compte", 40) {
                            onNavigate("visitor")
                        }
                    }
                }
                Surface(
                    modifier = Modifier.fillMaxWidth().height(53.dp),
                    color = Color.White.copy(alpha = .91f),
                    shape = RoundedCornerShape(30.dp),
                    shadowElevation = 7.dp
                ) {
                    Row(Modifier.fillMaxSize().padding(start=12.dp,end=5.dp),
                        verticalAlignment=Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Search,null,Modifier.size(23.dp),
                            tint=Color(0xFF38383D))
                        BasicTextField(
                            search,onSearch,singleLine=true,
                            textStyle=TextStyle(color=Color(0xFF34343B),fontSize=13.sp),
                            modifier=Modifier.weight(1f).padding(horizontal=10.dp),
                            decorationBox={ inner ->
                                Box {
                                    if(search.isBlank()) Text("Rechercher un terrain, une villa...",
                                        fontSize=12.sp,color=Color(0xFF6C6773),
                                        maxLines=1,overflow=TextOverflow.Ellipsis)
                                    inner()
                                }
                            }
                        )
                        VerticalDivider(Modifier.height(25.dp), color=Color(0x1A484848))
                        IconButton(onClick={onNotice("Filtres avancés : branchement API à venir")}) {
                            Icon(Icons.Outlined.Tune,"Filtres",tint=red)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    LockedCategory("Terrains", Icons.Outlined.Layers,selectedCategory, onCategory,
                        Modifier.weight(1f))
                    LockedCategory("Villas", Icons.Outlined.Home,selectedCategory,onCategory,
                        Modifier.weight(1f))
                    LockedCategory("Appartements", Icons.Outlined.Apartment,selectedCategory,onCategory,
                        Modifier.weight(1.17f))
                    LockedCategory("Fermes", Icons.Outlined.Park,selectedCategory,onCategory,
                        Modifier.weight(1f))
                    LockedCategory("Locaux commerciaux", Icons.Outlined.Storefront,selectedCategory,
                        onCategory,Modifier.weight(1.14f))
                }
            }
        }

        Column(
            modifier=Modifier.align(Alignment.CenterEnd).offset(y=(-32).dp).padding(end=9.dp),
            verticalArrangement=Arrangement.spacedBy(10.dp)
        ) {
            LockedRoundIcon(Icons.Outlined.NearMe, "Position", 44) {
                onNotice("Position non demandée : déplacement libre en mode aperçu")
            }
            LockedRoundIcon(Icons.Outlined.Layers, "Couches",44) {
                onNotice("Couches réelles disponibles après intégration de la carte")
            }
            LockedRoundIcon(Icons.Outlined.GpsFixed, "Centrer",44) {
                panX=0f;panY=0f
            }
            Surface(
                modifier=Modifier.size(44.dp).clickable {
                    onNotice("La carte 3D sera intégrée dans une prochaine étape")
                },
                color=Color.White.copy(alpha=.95f),shape=RoundedCornerShape(16.dp),
                shadowElevation=3.dp
            ) { Box(contentAlignment=Alignment.Center) {
                Text("3D",fontSize=18.sp,fontWeight=FontWeight.Bold,color=red)
            }}
        }

        // Small screenshot-style location indicators; never pass as cadastral data.
        if (!immersive) {
            Surface(
                modifier=Modifier.align(Alignment.CenterStart).padding(start=16.dp)
                    .offset(y=90.dp),
                color=red.copy(alpha=.91f),shape=RoundedCornerShape(50),
                border=BorderStroke(2.dp,Color.White),shadowElevation=8.dp
            ) {
                Icon(Icons.Outlined.Home,"Terrain démonstration",
                    Modifier.size(43.dp).clickable(onClick={immersive=false})
                        .padding(9.dp),tint=Color.White)
            }
            Surface(
                modifier=Modifier.align(Alignment.BottomStart)
                    .padding(start=15.dp,bottom=241.dp),
                color=Color(0xE0BC1026),shape=RoundedCornerShape(18.dp),
                shadowElevation=4.dp
            ) {
                Text("☀  28°C\nHammamet",
                    Modifier.padding(horizontal=13.dp,vertical=8.dp),
                    fontSize=13.sp,color=Color.White,lineHeight=17.sp,
                    fontWeight=FontWeight.Bold)
            }
        }

        AnimatedVisibility(
            visible=!immersive,
            modifier=Modifier.align(Alignment.BottomCenter),
            enter=fadeIn(tween(340))+slideInVertically(tween(380)){it/4},
            exit=fadeOut(tween(200))+slideOutVertically(tween(320)){it}
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal=9.dp)
                .padding(bottom=8.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                if(showCard) LockedPropertyCard(favorite,onFavorite,onOpen)
                else Surface(
                    Modifier.fillMaxWidth(),color=Color.White.copy(alpha=.92f),
                    shape=RoundedCornerShape(22.dp)
                ) {
                    Text("Aucun autre bien dans cet aperçu.",
                        Modifier.padding(14.dp),color=Color(0xFF5B5360),fontSize=12.sp)
                }
                LockedBottomNav(onNavigate,{immersive=true})
            }
        }

        AnimatedVisibility(
            visible=immersive,
            modifier=Modifier.align(Alignment.TopStart).padding(start=12.dp,top=13.dp),
            enter=fadeIn(tween(280)),exit=fadeOut(tween(150))
        ) {
            Surface(
                Modifier.clickable { immersive=false },
                shape=RoundedCornerShape(25.dp),color=Color.White.copy(alpha=.94f),
                shadowElevation=5.dp
            ) {
                Row(Modifier.padding(horizontal=15.dp,vertical=10.dp),
                    verticalAlignment=Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Search,null,tint=red,modifier=Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Rechercher",fontSize=12.sp,fontWeight=FontWeight.Bold,color=red)
                }
            }
        }
        AnimatedVisibility(
            visible=immersive,
            modifier=Modifier.align(Alignment.BottomCenter).padding(bottom=19.dp),
            enter=fadeIn(tween(290)),exit=fadeOut(tween(180))
        ) {
            Surface(
                Modifier.clickable { immersive=false },
                shape=RoundedCornerShape(26.dp),color=Color.White.copy(alpha=.94f),
                shadowElevation=5.dp
            ) {
                Text("⌃  Voir les biens",Modifier.padding(horizontal=23.dp,vertical=13.dp),
                    color=red,fontSize=14.sp,fontWeight=FontWeight.Bold)
            }
        }
        if(notice.isNotBlank()) {
            Surface(
                modifier=Modifier.align(Alignment.Center).padding(20.dp)
                    .clickable { onNotice("") },
                shape=RoundedCornerShape(15.dp),color=Color(0xFFFDEEF0),
                shadowElevation=6.dp
            ){
                Text(notice+"   ×",Modifier.padding(14.dp),fontSize=12.sp,
                    color=Color(0xFF9D0C21))
            }
        }
    }
}

@Composable
private fun LockedRoundIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description:String,
    diameter:Int,
    onClick:()->Unit
) {
    Surface(
        modifier=Modifier.size(diameter.dp).clickable(onClick=onClick),
        color=Color.White.copy(alpha=.91f),
        shape=RoundedCornerShape(18.dp),shadowElevation=4.dp
    ) {
        Box(contentAlignment=Alignment.Center) {
            Icon(icon,description,modifier=Modifier.size(23.dp),tint=Color(0xFFE22035))
        }
    }
}

@Composable
private fun LockedCategory(
    name:String, icon:androidx.compose.ui.graphics.vector.ImageVector, selected:String,
    onSelect:(String)->Unit, modifier:Modifier
) {
    val active=name==selected || (name=="Terrains" && selected=="أراضي")
    Surface(
        modifier=modifier.height(68.dp).clickable { onSelect(name) },
        color=if(active)Color(0xFFE50927) else Color.White.copy(alpha=.92f),
        shape=RoundedCornerShape(16.dp),
        border=BorderStroke(1.dp,Color.White.copy(alpha=.55f)),
        shadowElevation=4.dp
    ) {
        Column(
            Modifier.padding(horizontal=2.dp,vertical=7.dp),
            verticalArrangement=Arrangement.spacedBy(3.dp),
            horizontalAlignment=Alignment.CenterHorizontally
        ) {
            Icon(icon,null,tint=if(active)Color.White else Color(0xFFDD1229),
                modifier=Modifier.size(24.dp))
            Text(name, fontSize=9.sp, lineHeight=10.sp,
                color=if(active)Color.White else Color(0xFF242027),
                maxLines=2,textAlign=TextAlign.Center,
                fontWeight=FontWeight.SemiBold)
        }
    }
}

@Composable
private fun LockedPropertyCard(
    favorite:Boolean, onFavorite:()->Unit,onOpen:()->Unit
) {
    Surface(
        modifier=Modifier.fillMaxWidth(),
        color=Color(0xF8FFF8FC),
        shape=RoundedCornerShape(27.dp),
        border=BorderStroke(1.dp,Color.White),
        shadowElevation=10.dp
    ) {
        Row(
            Modifier.fillMaxWidth().padding(11.dp).height(151.dp),
            horizontalArrangement=Arrangement.spacedBy(11.dp)
        ) {
            Box(Modifier.weight(.91f).fillMaxHeight().clip(RoundedCornerShape(18.dp))) {
                Image(painterResource(R.drawable.malak_property_photo),
                    contentDescription="Photo illustrative non vérifiée",
                    contentScale=ContentScale.Crop,modifier=Modifier.fillMaxSize())
                Surface(
                    Modifier.align(Alignment.TopStart).padding(7.dp),
                    color=Color.White.copy(alpha=.94f),
                    shape=RoundedCornerShape(20.dp)
                ) {
                    Text("Aperçu · Démo",Modifier.padding(horizontal=8.dp,vertical=5.dp),
                        fontSize=10.sp,color=Color(0xFFD90929),
                        fontWeight=FontWeight.Bold)
                }
                Icon(Icons.Outlined.Image,"Images de démonstration",
                    Modifier.align(Alignment.BottomEnd).padding(8.dp).size(21.dp),
                    tint=Color.White)
            }
            Column(Modifier.weight(1.05f).fillMaxHeight(),
                verticalArrangement=Arrangement.SpaceBetween) {
                Row(verticalAlignment=Alignment.Top) {
                    Text("Terrain à Hammamet",fontSize=16.sp,lineHeight=19.sp,
                        fontWeight=FontWeight.Bold,color=Color(0xFF1B1B23),
                        modifier=Modifier.weight(1f),maxLines=2)
                    Icon(Icons.Outlined.FavoriteBorder,"Ajouter aux favoris",
                        Modifier.size(28.dp).clickable(onClick=onFavorite)
                            .padding(2.dp),
                        tint=if(favorite)Color.Red else Color(0xFFDD5060))
                }
                Text("▧ 900 m²   │   ♧ Proche de la mer",
                    fontSize=10.sp,maxLines=1,
                    overflow=TextOverflow.Ellipsis,color=Color(0xFF494750))
                Text("540 000 TND",fontSize=20.sp,fontWeight=FontWeight.Bold,
                    color=Color(0xFFF0092D))
                Surface(
                    modifier=Modifier.fillMaxWidth().clickable(onClick=onOpen),
                    color=Color(0xFFF01127),shape=RoundedCornerShape(25.dp),
                    shadowElevation=4.dp
                ) {
                    Text("Voir les détails  →",Modifier.padding(vertical=10.dp),
                        textAlign=TextAlign.Center,fontSize=12.sp,
                        fontWeight=FontWeight.Bold,color=Color.White,
                        maxLines=1)
                }
            }
        }
    }
}

@Composable
private fun LockedBottomNav(
    onNavigate:(String)->Unit,
    onMap:()->Unit
) {
    Surface(
        Modifier.fillMaxWidth().height(65.dp),
        color=Color(0xF4FFFAFC),
        shape=RoundedCornerShape(34.dp),
        shadowElevation=7.dp
    ) {
        Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.SpaceEvenly,
            verticalAlignment=Alignment.CenterVertically) {
            LockedNavItem("Accueil",Icons.Outlined.Home,true) { onNavigate("home") }
            LockedNavItem("Carte",Icons.Outlined.Map,false,onMap)
            LockedNavItem("Favoris",Icons.Outlined.FavoriteBorder,false) {
                onNavigate("favorites")
            }
            LockedNavItem("Plus",Icons.Outlined.GridView,false) {
                onNavigate("visitor")
            }
        }
    }
}

@Composable
private fun LockedNavItem(
    label:String,icon:androidx.compose.ui.graphics.vector.ImageVector,active:Boolean,
    onClick:()->Unit
) {
    Column(
        modifier=Modifier.clickable(onClick=onClick)
            .padding(horizontal=5.dp,vertical=5.dp),
        horizontalAlignment=Alignment.CenterHorizontally,
        verticalArrangement=Arrangement.spacedBy(1.dp)
    ) {
        Icon(icon,contentDescription=label,modifier=Modifier.size(25.dp),
            tint=if(active)Color(0xFFF00B27) else Color(0xFF4C4A51))
        Text(label,fontSize=11.sp,color=if(active)Color(0xFFDE0A2C)
            else Color(0xFF34343B),fontWeight=if(active)FontWeight.Bold else FontWeight.Normal)
    }
}
