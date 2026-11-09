package com.mohit.ghostframe

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val Ink = Color(0xFF0B0D0C)
private val Card = Color(0xFF16191A)
private val Gold = Color(0xFFFFD34D)
private val Muted = Color(0xFFA6AAA3)

class MainActivity : ComponentActivity() {
    val pickRequests = mutableIntStateOf(0)
    val sharedUri = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Gold, onPrimary = Ink, background = Ink, surface = Card,
                    onBackground = Color(0xFFF3F1EC), onSurface = Color(0xFFF3F1EC)
                )
            ) {
                Surface(Modifier.fillMaxSize(), color = Ink) { Screen(this) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        if (i == null) return
        if (i.getBooleanExtra("pick", false)) { pickRequests.intValue++; i.removeExtra("pick") }
        if (i.action == Intent.ACTION_SEND) {
            @Suppress("DEPRECATION")
            (i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { sharedUri.value = it }
        }
    }
}

@Composable
private fun Screen(act: MainActivity) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()
    val maxA = remember { maxPassThroughAlpha(ctx) }

    var version by remember { mutableLongStateOf(prefs.refVersion) }
    var ref by remember { mutableStateOf<ImageBitmap?>(null) }
    var canOverlay by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }
    var running by remember { mutableStateOf(OverlayService.running) }
    var opacity by remember { mutableFloatStateOf(prefs.opacity.coerceAtMost(maxA)) }
    var outline by remember { mutableStateOf(prefs.outline) }
    var detail by remember { mutableFloatStateOf(prefs.detail.toFloat()) }
    var color by remember { mutableIntStateOf(prefs.color) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(version) { ref = withContext(Dispatchers.IO) { RefStore.load(ctx)?.asImageBitmap() } }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) { canOverlay = Settings.canDrawOverlays(ctx); running = OverlayService.running }
        }
        lifecycle.addObserver(obs); onDispose { lifecycle.removeObserver(obs) }
    }

    fun reload() { OverlayService.send(ctx, OverlayService.ACTION_RELOAD) }

    fun importUri(uri: Uri) {
        busy = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { RefStore.saveFromUri(ctx, uri) }.isSuccess }
            busy = false
            if (ok) { version = prefs.refVersion; reload() }
            else Toast.makeText(ctx, "Could not open that image", Toast.LENGTH_SHORT).show()
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) importUri(uri)
    }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    val pickReq by act.pickRequests
    LaunchedEffect(pickReq) {
        if (pickReq > 0) picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    val shared by act.sharedUri
    LaunchedEffect(shared) { shared?.let { importUri(it); act.sharedUri.value = null } }

    fun start() {
        if (!canOverlay) {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        OverlayService.send(ctx, OverlayService.ACTION_START)
        running = true
        launchCamera(ctx)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Ghost Frame", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            Text("Float a see-through photo over your camera app and match the pose.", color = Muted, fontSize = 14.sp)
        }

        // Reference
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.8f).clip(RoundedCornerShape(20.dp)).background(Card)
                .clickable { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            contentAlignment = Alignment.Center
        ) {
            val r = ref
            if (r != null) Image(r, "Reference photo", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("No reference yet", fontWeight = FontWeight.Medium)
                Text("Tap to choose a photo, or share one to Ghost Frame", color = Muted, fontSize = 13.sp)
            }
            if (busy) CircularProgressIndicator(color = Gold)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                Modifier.weight(1f), shape = RoundedCornerShape(14.dp)
            ) { Text("Choose photo", fontWeight = FontWeight.SemiBold) }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { RefStore.save(ctx, RefStore.samplePose()) }
                        version = prefs.refVersion; reload()
                    }
                },
                Modifier.weight(1f), shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Color(0x33FFFFFF))
            ) { Text("Sample pose", color = Color.White) }
        }

        // Style
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Card).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0x14FFFFFF)).padding(3.dp)) {
                listOf(false to "Photo", true to "Outline").forEach { (v, label) ->
                    val on = outline == v
                    Box(
                        Modifier.clip(RoundedCornerShape(9.dp)).background(if (on) Color(0xFFF3F1EC) else Color.Transparent)
                            .clickable { outline = v; prefs.outline = v; reload() }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) { Text(label, color = if (on) Ink else Muted, fontWeight = FontWeight.Medium) }
                }
            }
            LabeledSlider("Ghost", "${(opacity * 100).roundToInt()}%", opacity, 0.1f..maxA,
                onChange = { opacity = it; prefs.opacity = it; reload() })
            if (outline) {
                LabeledSlider("Detail", "${detail.roundToInt()}", detail, 0f..100f,
                    onChange = { detail = it }, onDone = { prefs.detail = detail.roundToInt(); reload() })
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("LINES", color = Muted, fontSize = 12.sp, letterSpacing = 1.sp, modifier = Modifier.width(56.dp))
                    OUTLINE_COLORS.forEachIndexed { i, c ->
                        Box(
                            Modifier.size(30.dp).clip(CircleShape).background(Color(c))
                                .border(if (color == i) 3.dp else 1.dp, if (color == i) Gold else Color(0x55FFFFFF), CircleShape)
                                .clickable { color = i; prefs.color = i; reload() }
                        )
                    }
                }
            }
            Text(
                "Ghost tops out at ${(maxA * 100).roundToInt()}% because Android blocks taps to the camera through anything more solid.",
                color = Muted, fontSize = 12.sp
            )
        }

        // Start
        if (!canOverlay) {
            Text("Ghost Frame needs \"Display over other apps\" so the photo can sit on top of your camera.", color = Muted, fontSize = 13.sp)
        }
        Button(
            onClick = { start() }, Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp),
            enabled = ref != null || !canOverlay
        ) {
            Text(
                if (!canOverlay) "Allow display over other apps" else if (running) "Open camera" else "Start overlay & open camera",
                fontWeight = FontWeight.SemiBold, fontSize = 16.sp
            )
        }
        if (running) {
            OutlinedButton(
                onClick = { OverlayService.send(ctx, OverlayService.ACTION_STOP); running = false },
                Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color(0x33FFFFFF))
            ) { Text("Stop overlay", color = Color.White) }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("ON THE CAMERA", color = Muted, fontSize = 12.sp, letterSpacing = 1.sp)
            Tip("Tap the floating bubble to open controls. Drag it to move it out of the way.")
            Tip("Move: drag the photo, pinch to resize and rotate, then tap Done. Taps go to the camera again.")
            Tip("− and + change how see-through it is. Edge switches to outlines only.")
            Tip("The overlay never appears in your photos. It only sits on the screen.")
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun LabeledSlider(
    label: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit, onDone: () -> Unit = {}
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label.uppercase(), color = Muted, fontSize = 12.sp, letterSpacing = 1.sp, modifier = Modifier.width(56.dp))
        Slider(v, onChange, Modifier.weight(1f), valueRange = range, onValueChangeFinished = onDone,
            colors = SliderDefaults.colors(thumbColor = Color(0xFFF3F1EC), activeTrackColor = Gold, inactiveTrackColor = Color(0x33FFFFFF)))
        Text(value, color = Muted, fontSize = 12.sp, modifier = Modifier.width(40.dp).padding(start = 8.dp))
    }
}

@Composable
private fun Tip(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.padding(top = 7.dp).size(5.dp).clip(CircleShape).background(Gold))
        Text(text, fontSize = 14.sp, color = Color(0xFFD9D7D1))
    }
}
