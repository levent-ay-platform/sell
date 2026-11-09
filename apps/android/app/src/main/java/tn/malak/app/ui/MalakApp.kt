package tn.malak.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Landscape
import androidx.compose.material.icons.outlined.Apartment
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Park
import androidx.compose.material.icons.outlined.Storefront

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val PreviewLand = PreviewListing(
    title = "أرض مطلّة على البحر",
    location = "قليبية، نابل",
    priceTnd = "420,000",
    area = "1,200 م²",
    type = "أرض سكنية"
)

private data class PreviewListing(
    val title: String, val location: String, val priceTnd: String,
    val area: String, val type: String
)

/** Fully local, synthetic prototype of the customer-facing design. No fake verified API state. */
@Composable
fun MalakApp() {
    var screen by rememberSaveable { mutableStateOf("home") }
    var search by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("Terrains") }
    var favorite by rememberSaveable { mutableStateOf(false) }
    var compare by rememberSaveable { mutableStateOf(false) }
    var map3d by rememberSaveable { mutableStateOf(true) }
    var notice by rememberSaveable { mutableStateOf("") }

    MaterialTheme(colorScheme = lightColorScheme(primary = Brand.Crimson, background = Brand.Pearl)) {
        Surface(color = Brand.Pearl, modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            when(screen) {
                "home" -> MalakMapHome(
                    search = search, onSearch = { search = it },
                    selectedCategory = category, onCategory = { category = it },
                    favorite = favorite, onFavorite = { favorite = !favorite },
                    map3d = map3d, onToggleMap = { map3d = !map3d },
                    onOpen = { screen = "details" },
                    onNavigate = { screen = it }, notice = notice,
                    onNotice = { notice = it }
                )
                "details" -> DetailScreen(
                    favorite = favorite, onFavorite = { favorite = !favorite },
                    onBack = { screen = "home" },
                    onCompare = { compare = true; screen = "compare" },
                    onContact = { notice = "التواصل الحقيقي يبدأ بعد ربط الحسابات والبيانات الموثقة"; screen = "home" }
                )
                "favorites" -> SimplePage(
                    title = "المفضلة", onBack = { screen = "home" },
                    bottom = { BottomNav(active=screen,onNavigate={ screen = it }) }
                ) {
                    if (favorite) SmallListingCard(onClick = { screen = "details" })
                    else MessageBlock("عقاراتك المفضّلة", "إضغط على ♡ داخل أي عقار لإضافته هنا. التفضيل حالياً ضمن معاينة التطبيق.")
                }
                "compare" -> SimplePage(
                    title = "مقارنة العقارات", onBack = { screen = "home" },
                    bottom = { BottomNav(active=screen,onNavigate={ screen = it }) }
                ) {
                    MessageBlock("قارن بذكاء", "المقارنة الحقيقية ستعتمد فقط على عروض منشورة ومتحققة من الخادم.")
                    if (compare) {
                        SmallListingCard(onClick = { screen = "details" })
                        InfoRow("المساحة", "1,200 م²")
                        InfoRow("سعر المتر", "350 د.ت — قيمة توضيحية")
                        InfoRow("مستوى المراجعة", "لا توجد مراجعة حقيقية في المعاينة")
                    }
                    MalakAction("ارجع لاختيار عقار") { screen = "home" }
                }
                "seller" -> SellerDraftScreen(onBack = { screen = "home" })
                "visitor" -> SimplePage(
                    title = "حسابي", onBack = { screen = "home" },
                    bottom = { BottomNav(active=screen,onNavigate={ screen = it }) }
                ) {
                    MessageBlock("أهلاً بك في ملاك", "تقدر تستكشف العقارات بدون حساب. التسجيل مطلوب فقط عند إضافة عقار أو التواصل الحقيقي مع المالك.")
                    InfoRow("اللغة", "العربية / Français")
                    InfoRow("الهوية", "مستخدم زائر")
                    InfoRow("الخصوصية", "لا توجد وثائق شخصية في هذا النموذج")
                    MalakAction("إضافة عقار") { screen = "seller" }
                }
                else -> MalakMapHome(
                    search, { search = it }, category, { category = it }, favorite,
                    { favorite = !favorite }, map3d, { map3d = !map3d },
                    { screen = "details" }, { screen = it }, notice, { notice = it }
                )
            }
        }
    }
}

@Composable
private fun HomeScreen(
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
    var zoom by rememberSaveable { mutableIntStateOf(1) }
    val matches = (search.isBlank() ||
        listOf("قليبية","أرض","بحر","نابل","kelibia","kélibia").any { search.contains(it,ignoreCase = true) }) &&
        (selectedCategory == "أراضي" || selectedCategory == "الكل")

    Box(Modifier.fillMaxSize().background(
        Brush.verticalGradient(listOf(Color(0xFFFCE9F0), Color(0xFFCAE8FC), Brand.Pearl))
    )) {
        BougainvilleaSway(Modifier.align(Alignment.TopEnd)
            .padding(end=3.dp,top=2.dp).size(width=118.dp,height=76.dp))
        Column(
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom=93.dp)
        ) {
            BrandHeader(onAccount = { onNavigate("visitor") })
            Spacer(Modifier.height(3.dp))
            SearchPill(value=search,onValue=onSearch,onAI={onNotice("البحث الذكي سيُربط بالبيانات العقارية الموثقة")})
            Spacer(Modifier.height(6.dp))
            CategoryChips(selectedCategory,onCategory)
            Box(
                Modifier.fillMaxWidth().height(190.dp)
            ) {
                ScenicBackdrop(Modifier.fillMaxSize(), previewZoom=zoom)
                Column(Modifier.align(Alignment.CenterEnd).padding(end=12.dp), verticalArrangement=Arrangement.spacedBy(9.dp)) {
                    CircleControl("▱") { onNotice("طبقات الخريطة الحقيقية ضمن مرحلة دمج المحرك") }
                    CircleControl("3D") { onNotice("العرض الثلاثي الحقيقي غير مفعّل في هذه المعاينة") }
                    CircleControl("+") { zoom = (zoom+1).coerceAtMost(6) }
                    CircleControl("−") { zoom = (zoom-1).coerceAtLeast(1) }
                }
                Surface(
                    modifier=Modifier.align(Alignment.BottomStart).padding(start=12.dp,bottom=10.dp),
                    color=Color.White.copy(alpha=.88f),
                    shape=RoundedCornerShape(16.dp),
                    shadowElevation=3.dp
                ) {
                    Text("قليبية · معاينة غير تفاعلية · $zoom", Modifier.padding(horizontal=12.dp,vertical=10.dp),
                        fontSize=12.sp,color=Brand.Ink)
                }
                Surface(
                    modifier=Modifier.align(Alignment.TopStart).padding(start=12.dp,top=8.dp),
                    color=Brand.Pearl.copy(alpha=.82f),shape=RoundedCornerShape(10.dp)
                ) {
                    Text("مشهد مرجعي · خريطة فعلية قريباً",
                        Modifier.padding(horizontal=8.dp,vertical=5.dp),
                        color=Brand.Crimson,fontSize=10.sp)
                }
            }
            if (matches) PreviewPropertyCard(favorite,onFavorite,onOpen)
            else MessageBlock("لا توجد نتائج في المعاينة", "جرّب البحث عن قليبية أو أرض. النتائج الحقيقية تحتاج ربط واجهة الاستكشاف.")
            if (notice.isNotBlank()) {
                Surface(Modifier.fillMaxWidth().padding(12.dp).clickable { onNotice("") },
                    color=Brand.Rose,shape=RoundedCornerShape(14.dp)) {
                    Text(notice + "  ×",Modifier.padding(12.dp),fontSize=12.sp,color=Brand.Crimson)
                }
            }
        }
        BottomNav("home",onNavigate,Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun BrandHeader(onAccount: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(start=14.dp,end=14.dp,top=2.dp),
        verticalAlignment=Alignment.CenterVertically,
        horizontalArrangement=Arrangement.SpaceBetween
    ) {
        Column {
            Text(APP_BRAND_ARABIC,color=Brand.Crimson,fontSize=27.sp,lineHeight=30.sp,fontWeight=FontWeight.Bold)
            Text("REAL ESTATE TUNISIA",color=Brand.Crimson,fontSize=8.sp,letterSpacing=1.2.sp)
        }
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text("عقارات تونس .. مباشرة وموثوقة", color=Brand.Ink,fontSize=10.sp)
            Spacer(Modifier.width(7.dp))
            Text("🇹🇳",Modifier.clip(RoundedCornerShape(14.dp))
                .background(Brand.Crimson).padding(7.dp).clickable(onClick=onAccount),
                fontSize=19.sp)
        }
    }
}

@Composable
private fun SearchPill(value:String,onValue:(String)->Unit,onAI:()->Unit) {
    Surface(
        modifier=Modifier.fillMaxWidth().padding(horizontal=12.dp),
        shape=RoundedCornerShape(24.dp),color=Color.White.copy(alpha=.91f),shadowElevation=5.dp
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min=46.dp).padding(horizontal=10.dp),
            verticalAlignment=Alignment.CenterVertically) {
            Icon(imageVector=Icons.Outlined.Search,contentDescription="بحث",
                tint=Brand.Crimson,modifier=Modifier.padding(horizontal=8.dp).size(24.dp))
            BasicTextField(
                value=value,onValueChange=onValue,singleLine=true,
                modifier=Modifier.weight(1f),
                textStyle=TextStyle(fontSize=14.sp,color=Brand.Ink),
                decorationBox={ inner ->
                    Box {
                        if(value.isEmpty()) Text("ابحث عن أرض، منزل، فيلا، أو منطقة...",fontSize=13.sp,
                            color=Brand.Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
                        inner()
                    }
                }
            )
            Icon(imageVector=Icons.Outlined.Mic,contentDescription="البحث الصوتي",
                tint=Brand.Crimson,modifier=Modifier.size(30.dp).clickable(onClick=onAI).padding(4.dp))
            Surface(color=Brand.Crimson,shape=RoundedCornerShape(18.dp),modifier=Modifier.clickable(onClick=onAI)) {
                Text("✦",Modifier.padding(horizontal=16.dp,vertical=9.dp),fontSize=20.sp,color=Color.White)
            }
        }
    }
}

@Composable
private fun CategoryChips(selected:String,onSelect:(String)->Unit) {
    val items=listOf(
        "أراضي" to Icons.Outlined.Landscape,
        "شقق" to Icons.Outlined.Apartment,
        "فيلات" to Icons.Outlined.Home,
        "مزارع" to Icons.Outlined.Park,
        "تجارية" to Icons.Outlined.Storefront
    )
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=12.dp),
        horizontalArrangement=Arrangement.spacedBy(9.dp)
    ) {
        items.forEach { (label,icon) ->
            val chosen=selected==label
            Surface(
                modifier=Modifier.clickable { onSelect(label) },
                color=if(chosen) Brand.Crimson else Color.White.copy(alpha=.91f),
                shape=RoundedCornerShape(17.dp),shadowElevation=2.dp
            ) {
                Column(Modifier.padding(horizontal=12.dp,vertical=5.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                    Icon(imageVector=icon,contentDescription=null,
                        tint=if(chosen)Color.White else Brand.Crimson,
                        modifier=Modifier.size(21.dp))
                    Text(label,color=if(chosen)Color.White else Brand.Ink,fontSize=11.sp,fontWeight=FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun CircleControl(text:String,onClick:()->Unit) {
    Surface(
        modifier=Modifier.size(48.dp).clickable(onClick=onClick),shape=RoundedCornerShape(50),
        color=Brand.Pearl.copy(alpha=.95f),shadowElevation=3.dp
    ) {
        Box(contentAlignment=Alignment.Center) {
            when(text) {
                "+" -> Icon(Icons.Outlined.Add,contentDescription="تكبير",tint=Brand.Crimson)
                "−" -> Icon(Icons.Outlined.Remove,contentDescription="تصغير",tint=Brand.Crimson)
                "▱" -> Icon(Icons.Outlined.Layers,contentDescription="طبقات الخريطة",tint=Brand.Crimson)
                else -> Text(text,color=Brand.Crimson,fontSize=14.sp,fontWeight=FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun PreviewPropertyCard(favorite:Boolean,onFavorite:()->Unit,onOpen:()->Unit) {
    Surface(
        modifier=Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=2.dp),
        color=Color(0xF7FFFAFB),shape=RoundedCornerShape(27.dp),
        border=BorderStroke(1.dp,Color.White.copy(alpha=.95f)),
        shadowElevation=11.dp
    ) {
        Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment=Alignment.Top, horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Box(Modifier.fillMaxWidth().height(102.dp).clip(RoundedCornerShape(15.dp))) {
                        CoastalThumbnail(Modifier.fillMaxSize())
                        Text("معاينة تصميم",
                            Modifier.align(Alignment.BottomStart).padding(5.dp)
                                .background(Color.White.copy(alpha=.86f),RoundedCornerShape(8.dp))
                                .padding(horizontal=6.dp,vertical=3.dp),
                            fontSize=9.sp,color=Brand.Crimson)
                    }
                    Spacer(Modifier.height(5.dp))
                    Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                        for(index in 0..2){
                            Box(Modifier.weight(1f).height(27.dp)) {
                                PreviewGalleryThumbnail(index,Modifier.fillMaxSize())
                            }
                        }
                    }
                }
                Column(Modifier.weight(1f),horizontalAlignment=Alignment.End,
                    verticalArrangement=Arrangement.spacedBy(3.dp)) {
                    Text("◈  قيد التحقق",color=Brand.Crimson,fontSize=11.sp,
                        fontWeight=FontWeight.SemiBold,modifier=Modifier
                            .background(Brand.Rose,RoundedCornerShape(15.dp))
                            .padding(horizontal=9.dp,vertical=5.dp))
                    Text("#TUN-84527  ·  نموذج",fontSize=9.sp,color=Brand.Muted)
                    Text(PreviewLand.title,fontWeight=FontWeight.Bold,fontSize=15.sp,
                        color=Brand.Ink,lineHeight=20.sp,textAlign=TextAlign.Right)
                    Text("⌖  "+PreviewLand.location,fontSize=11.sp,color=Brand.Muted)
                    Text(PreviewLand.priceTnd+" د.ت",fontSize=18.sp,color=Brand.Crimson,
                        fontWeight=FontWeight.Bold)
                }
            }
            HorizontalDivider(color=Color(0xFFE9D8D9))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
                DetailFact("المساحة",PreviewLand.area)
                DetailFact("نوع العقار",PreviewLand.type)
                DetailFact("الملكية","غير معتمدة")
            }
            Row(verticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                CircleControl(if(favorite) "♥" else "♡",onFavorite)
                Box(Modifier.weight(1f)) {
                    MalakAction("عرض التفاصيل    ⟶",onOpen)
                }
            }
        }
    }
}

@Composable
private fun DetailFact(label:String,value:String) {
    Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(3.dp)) {
        Text(label,fontSize=11.sp,color=Brand.Muted)
        Text(value,fontSize=12.sp,fontWeight=FontWeight.SemiBold,color=Brand.Ink)
    }
}

@Composable
private fun BottomNav(active:String,onNavigate:(String)->Unit,modifier:Modifier=Modifier) {
    Surface(
        modifier.fillMaxWidth().padding(horizontal=13.dp).padding(bottom=7.dp),
        color=Color.White.copy(alpha=.96f),shape=RoundedCornerShape(34.dp),
        border=BorderStroke(1.dp,Color.White),shadowElevation=8.dp
    ) {
        Row(
            Modifier.fillMaxWidth().height(59.dp).padding(horizontal=13.dp),
            verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.SpaceEvenly
        ) {
            NavItem("⌖","استكشف",active=="home") { onNavigate("home") }
            Surface(
                modifier=Modifier.size(60.dp).clickable { onNavigate("home") },
                color=Brand.Crimson,shape=RoundedCornerShape(50),
                border=BorderStroke(3.dp,Color(0xFFFFDDE3)),shadowElevation=7.dp
            ) {
                Box(contentAlignment=Alignment.Center) {
                    Text("🇹🇳",fontSize=29.sp,color=Color.White)
                }
            }
            NavItem("◎","الفرص القريبة",active=="favorites") { onNavigate("favorites") }
        }
    }
}

@Composable
private fun NavItem(icon:String,label:String,selected:Boolean,onClick:()->Unit) {
    Column(Modifier.clickable(onClick=onClick).padding(horizontal=4.dp),
        horizontalAlignment=Alignment.CenterHorizontally) {
        Icon(
            imageVector=if(icon=="⌖") Icons.Outlined.Explore else Icons.Outlined.Place,
            contentDescription=null,
            tint=if(selected)Brand.Crimson else Brand.Ink,
            modifier=Modifier.size(24.dp)
        )
        Text(label,fontSize=11.sp,color=if(selected)Brand.Crimson else Brand.Muted,
            fontWeight=if(selected)FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun MalakAction(label:String,onClick:()->Unit) {
    Surface(
        modifier=Modifier.fillMaxWidth().clickable(onClick=onClick),
        shape=RoundedCornerShape(25.dp),
        color=Brand.Crimson,shadowElevation=3.dp
    ) {
        Box(Modifier.padding(horizontal=15.dp,vertical=14.dp),contentAlignment=Alignment.Center) {
            Text(label,color=Color.White,fontSize=15.sp,fontWeight=FontWeight.SemiBold)
        }
    }
}

@Composable
private fun DetailScreen(
    favorite:Boolean,onFavorite:()->Unit,onBack:()->Unit,onCompare:()->Unit,onContact:()->Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom=18.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp),horizontalArrangement=Arrangement.SpaceBetween,
            verticalAlignment=Alignment.CenterVertically) {
            Text("‹  رجوع",Modifier.clickable(onClick=onBack),color=Brand.Crimson,fontSize=16.sp)
            Text("تفاصيل العقار",fontWeight=FontWeight.Bold,color=Brand.Ink,fontSize=18.sp)
            Text(if(favorite)"♥" else "♡",Modifier.clickable(onClick=onFavorite),color=Brand.Crimson,fontSize=25.sp)
        }
        Box(Modifier.fillMaxWidth().height(260.dp)) {
            CoastalThumbnail(Modifier.fillMaxSize())
            Surface(Modifier.align(Alignment.BottomStart).padding(12.dp),color=Brand.Glass,
                shape=RoundedCornerShape(18.dp)) {
                Text("صور وتصوير 360° بعد رفعها من المالك",Modifier.padding(10.dp),fontSize=11.sp,color=Brand.Ink)
            }
        }
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(DEMO_NOTE,fontSize=11.sp,color=Brand.Crimson)
            Text(PreviewLand.title,fontWeight=FontWeight.Bold,color=Brand.Ink,fontSize=24.sp)
            Text(PreviewLand.location,fontSize=14.sp,color=Brand.Muted)
            Text(PreviewLand.priceTnd+" د.ت",fontWeight=FontWeight.Bold,color=Brand.Crimson,fontSize=26.sp)
            Surface(color=Brand.Rose,shape=RoundedCornerShape(18.dp)) {
                Text("هذا العقار تجريبي؛ لم تُراجع هويته أو ملكيته ولا حدوده رسمياً.",
                    Modifier.padding(12.dp),fontSize=13.sp,color=Brand.Crimson)
            }
            InfoRow("المساحة",PreviewLand.area)
            InfoRow("نوع العقار",PreviewLand.type)
            InfoRow("صفة البائع","غير محددة في بيانات المعاينة")
            InfoRow("دقة الحدود","رسم توضيحي، وليس مخططاً مساحياً")
            MalakAction("أضف للمقارنة",onCompare)
            MalakAction("تواصل مع المالك",onContact)
        }
    }
}

@Composable
private fun SmallListingCard(onClick:()->Unit) {
    Surface(Modifier.fillMaxWidth().clickable(onClick=onClick),color=Color.White,
        shape=RoundedCornerShape(22.dp),shadowElevation=3.dp) {
        Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
            CoastalThumbnail(Modifier.size(112.dp,90.dp).clip(RoundedCornerShape(12.dp)))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(PreviewLand.title,fontWeight=FontWeight.Bold,fontSize=16.sp,color=Brand.Ink)
                Text("نموذج تجريبي · "+PreviewLand.location,fontSize=11.sp,color=Brand.Muted)
                Text(PreviewLand.priceTnd+" د.ت",color=Brand.Crimson,fontWeight=FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SimplePage(
    title:String,onBack:()->Unit,bottom:@Composable ()->Unit,content:@Composable ColumnScope.()->Unit
) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFFE8F3FE),Brand.Pearl)))) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=16.dp)
            .padding(bottom=94.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
            Row(Modifier.fillMaxWidth().padding(top=20.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                Text("‹ رجوع",Modifier.clickable(onClick=onBack),color=Brand.Crimson)
                Text(title,fontWeight=FontWeight.Bold,fontSize=22.sp,color=Brand.Ink)
            }
            content()
        }
        Box(Modifier.align(Alignment.BottomCenter)){bottom()}
    }
}

@Composable
private fun MessageBlock(title:String,detail:String) {
    Surface(Modifier.fillMaxWidth(),color=Color.White.copy(alpha=.94f),
        shape=RoundedCornerShape(22.dp),shadowElevation=3.dp) {
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(title,fontWeight=FontWeight.Bold,color=Brand.Crimson,fontSize=18.sp)
            Text(detail,color=Brand.Ink,fontSize=14.sp,lineHeight=21.sp)
        }
    }
}

@Composable
private fun InfoRow(label:String,value:String) {
    Surface(Modifier.fillMaxWidth(),color=Color.White.copy(alpha=.92f),shape=RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(12.dp),horizontalArrangement=Arrangement.SpaceBetween) {
            Text(label,color=Brand.Muted,fontSize=13.sp)
            Text(value,color=Brand.Ink,fontSize=13.sp,fontWeight=FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SellerDraftScreen(onBack:()->Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var title by rememberSaveable { mutableStateOf("") }
    var area by rememberSaveable { mutableStateOf("") }
    var rights by rememberSaveable { mutableStateOf("مالك منفرد") }

    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFFE9F3FF),Brand.Pearl)))
            .verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement=Arrangement.spacedBy(16.dp)
    ) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text("‹ رجوع",Modifier.clickable { if(step>0) step-- else onBack() },color=Brand.Crimson)
            Text("أضف عقارك",fontSize=22.sp,fontWeight=FontWeight.Bold,color=Brand.Ink)
        }
        Text("الخطوة "+(step+1)+" من 3",color=Brand.Crimson,fontSize=13.sp)
        LinearProgressIndicator(progress={ (step+1)/3f },modifier=Modifier.fillMaxWidth(),
            color=Brand.Crimson,trackColor=Brand.Rose)
        when(step) {
            0 -> {
                MessageBlock("معلومات العقار","أضف وصفاً واضحاً. تستطيع الرجوع والتعديل قبل التقديم.")
                OutlinedTextField(title,{title=it},label={Text("عنوان العقار")},modifier=Modifier.fillMaxWidth())
                OutlinedTextField(area,{area=it},label={Text("المساحة بالمتر المربع")},modifier=Modifier.fillMaxWidth())
                InfoRow("نوع العقار","أرض — نموذج أولي")
            }
            1 -> {
                MessageBlock("الملكية والصلاحية","حدّد صفتك القانونية بدقة. لا يظهر الإعلان للعامة قبل موافقة المراجع.")
                listOf("مالك منفرد","شريك في الملكية","وريث","صاحب وكالة","ممثل شركة").forEach { s ->
                    Surface(Modifier.fillMaxWidth().clickable { rights=s },color=if(rights==s)Brand.Rose else Color.White,
                        shape=RoundedCornerShape(15.dp)) {
                        Text((if(rights==s)"✓  " else "") + s,Modifier.padding(16.dp),
                            color=Brand.Ink,fontSize=15.sp)
                    }
                }
            }
            else -> {
                MessageBlock("المستندات والتحقق","رفع بطاقة الهوية وعقود الملكية والوثائق سيُفعّل بعد إنشاء تخزين خاص وصلاحيات مراجعة. هذه نسخة معاينة فقط.")
                InfoRow("العقار",title.ifBlank { "غير محدد" })
                InfoRow("المساحة",area.ifBlank { "غير محددة" })
                InfoRow("الصفة",rights)
                InfoRow("الحالة","مسودة تجريبية، غير منشورة")
            }
        }
        MalakAction(if(step<2)"التالي" else "إنهاء المعاينة") {
            if(step<2) step++ else onBack()
        }
        Text("لا يتم إرسال بياناتك أو نشر العقار في هذا النموذج.",fontSize=12.sp,
            color=Brand.Muted,textAlign=TextAlign.Center,modifier=Modifier.fillMaxWidth())
    }
}
