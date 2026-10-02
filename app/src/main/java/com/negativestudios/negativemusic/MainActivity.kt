package com.negativestudios.negativemusic

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ExecutionException

private val AccentBlue = Color(0xFF2C8CFF)
private val AccentCyan = Color(0xFF14F3F1)
private val Dark = Color(0xFF070B10)
private val Panel = Color(0xFF0B1118)
private val Gray = Color(0xFFB3C0CE)

data class Song(val uri: String, val title: String, val artist: String, val album: String, val duration: Long, val size: Long, val albumId: Long, val path: String = "")
data class Playlist(val id: String, val name: String, val description: String = "", val cover: String = "", val songs: List<String> = emptyList())

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NegativeMusicApp() }
    }
}

private fun audioPermission() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

private fun scanMusic(context: Context): List<Song> {
    val base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val cols = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DATA)
    val out = mutableListOf<Song>()
    context.contentResolver.query(base, cols, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use { c ->
        val id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val title = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val artist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val album = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
        val dur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
        val size = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
        val albumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
        val path = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
        while (c.moveToNext()) {
            val key = c.getLong(id)
            out += Song(Uri.withAppendedPath(base, key.toString()).toString(), c.getString(title) ?: "Canción desconocida", c.getString(artist)?.takeUnless { it == "<unknown>" } ?: "Artista desconocido", c.getString(album)?.takeUnless { it == "<unknown>" } ?: "Álbum desconocido", c.getLong(dur), c.getLong(size), c.getLong(albumId), c.getString(path) ?: "")
        }
    }
    return out
}
private fun time(ms: Long) = String.format(Locale.getDefault(), "%d:%02d", ms.coerceAtLeast(0) / 60000, (ms.coerceAtLeast(0) / 1000) % 60)
private fun bytes(n: Long): String = when { n < 1024 -> "$n B"; n < 1024L*1024 -> String.format(Locale.getDefault(), "%.1f KB", n/1024.0); n < 1024L*1024*1024 -> String.format(Locale.getDefault(), "%.1f MB", n/(1024.0*1024)); else -> String.format(Locale.getDefault(), "%.2f GB", n/(1024.0*1024*1024)) }

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun NegativeMusicApp() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("negative_music", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var songs by remember { mutableStateOf(emptyList<Song>()) }
    var loading by remember { mutableStateOf(true) }
    var page by remember { mutableStateOf("Inicio") }
    var settingsSection by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    var theme by remember { mutableStateOf(prefs.getString("theme", "dark") ?: "dark") }
    var playlists by remember { mutableStateOf(readPlaylists(prefs)) }
    var favorites by remember { mutableStateOf(prefs.getStringSet("favorites", emptySet())?.toSet() ?: emptySet()) }
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var now by remember { mutableStateOf<Song?>(null) }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var shuffle by remember { mutableStateOf(false) }
    var repeat by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
    var toast by remember { mutableStateOf("") }
    var createDialog by remember { mutableStateOf(false) }
    var showAddSongsDialog by remember { mutableStateOf(false) }
    var selectedSongUris by remember { mutableStateOf<Set<String>>(emptySet()) }
    var addSongsSearch by remember { mutableStateOf("") }
    var editTarget by remember { mutableStateOf<Playlist?>(null) }
    var menuSong by remember { mutableStateOf<Song?>(null) }
    var addSong by remember { mutableStateOf<Song?>(null) }
    var showQueue by remember { mutableStateOf(false) }
    var showPlayer by remember { mutableStateOf(false) }
    var showTimer by remember { mutableStateOf(false) }
    var timerMins by remember { mutableIntStateOf(30) }
    var deadline by remember { mutableLongStateOf(0L) }
    var crossfade by remember { mutableFloatStateOf(prefs.getInt("crossfade", 0).toFloat()) }
    var mono by remember { mutableStateOf(prefs.getBoolean("mono", false)) }
    var normalize by remember { mutableStateOf(prefs.getBoolean("normalize", false)) }
    var volume by remember { mutableStateOf(prefs.getString("volume", "Normal") ?: "Normal") }
    var eqOn by remember { mutableStateOf(prefs.getBoolean("eq", false)) }
    var eqBands by remember { mutableStateOf((0..4).map { prefs.getInt("eqBand$it", 0).toFloat() }) }
    var clearDataDialog by remember { mutableStateOf(false) }
    var outputDialog by remember { mutableStateOf(false) }
    var outputNames by remember { mutableStateOf(emptyList<String>()) }
    val dark = when (theme) { "light" -> false; "system" -> isSystemInDarkTheme(); else -> true }
    val bg = if (dark) Dark else Color(0xFFF7F7F7)
    val fg = if (dark) Color.White else Color(0xFF171717)
    val surface = if (dark) Panel else Color.White
    val secondary = if (dark) Gray else Color(0xFF666666)

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        scope.launch { if (ok) songs = withContext(Dispatchers.IO) { scanMusic(ctx) }; loading = false }
    }
    DisposableEffect(ctx) {
        val future: ListenableFuture<MediaController> = MediaController.Builder(ctx, SessionToken(ctx, ComponentName(ctx, MusicPlaybackService::class.java))).buildAsync()
        future.addListener({ try { controller = future.get() } catch (_: ExecutionException) { toast = "No se pudo iniciar el reproductor." } catch (_: Exception) { toast = "Error al conectar el reproductor." } }, ContextCompat.getMainExecutor(ctx))
        onDispose { MediaController.releaseFuture(future); controller = null }
    }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(ctx, audioPermission()) == PackageManager.PERMISSION_GRANTED) {
            songs = withContext(Dispatchers.IO) { scanMusic(ctx) }; loading = false
        } else permission.launch(audioPermission())
    }
    DisposableEffect(controller, songs) {
        val p = controller ?: return@DisposableEffect onDispose {}
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                now = songs.firstOrNull { it.uri == mediaItem?.localConfiguration?.uri?.toString() }
                position = 0L; duration = p.duration.coerceAtLeast(0)
            }
            override fun onRepeatModeChanged(repeatModeValue: Int) { repeat = repeatModeValue }
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) { shuffle = shuffleModeEnabled }
        }
        p.addListener(listener)
        now = songs.firstOrNull { it.uri == p.currentMediaItem?.localConfiguration?.uri?.toString() }
        onDispose { p.removeListener(listener) }
    }
    LaunchedEffect(controller) {
        while (true) {
            controller?.let { p ->
                position = p.currentPosition.coerceAtLeast(0); duration = p.duration.coerceAtLeast(0)
                now = songs.firstOrNull { it.uri == p.currentMediaItem?.localConfiguration?.uri?.toString() } ?: now
            }
            if (deadline > 0L && System.currentTimeMillis() >= deadline && playing) { controller?.pause(); deadline = 0L; toast = "Temporizador: reproducción detenida." }
            delay(500)
        }
    }
    fun saveLists(value: List<Playlist>) {
        playlists = value
        prefs.edit().putString("playlists", JSONArray().apply { value.forEach { p -> put(JSONObject().put("id",p.id).put("name",p.name).put("description",p.description).put("cover",p.cover).put("songs",JSONArray(p.songs))) } }.toString()).apply()
    }
    fun saveFavorites(value: Set<String>) { favorites = value; prefs.edit().putStringSet("favorites", value).apply() }
    fun item(s: Song) = MediaItem.Builder().setMediaId(s.uri).setUri(Uri.parse(s.uri)).setMediaMetadata(MediaMetadata.Builder().setTitle(s.title).setArtist(s.artist).setAlbumTitle(s.album).build()).build()
    fun play(list: List<Song>, start: Song? = null) {
        val p = controller
        if (list.isEmpty()) { toast = "No hay canciones para reproducir."; return }
        if (p == null) { toast = "El reproductor se está iniciando."; return }
        p.setMediaItems(list.map(::item), start?.let { s -> list.indexOfFirst { it.uri == s.uri }.coerceAtLeast(0) } ?: 0, 0L)
        p.shuffleModeEnabled = shuffle; p.repeatMode = repeat; p.prepare(); p.play(); now = start ?: list.first()
    }
    fun favorite(s: Song) = saveFavorites(if (s.uri in favorites) favorites - s.uri else favorites + s.uri)
    fun queue(s: Song) { val p = controller; if (p == null) toast = "El reproductor se está iniciando." else if (p.mediaItemCount == 0) play(listOf(s)) else { p.addMediaItem(item(s)); toast = "Añadida a la fila." } }
    val selectedPlaylist = playlists.firstOrNull { page == "playlist:" + it.id }
    val downloadedSongs = songs.filter { song -> song.path.replace('\\', '/').lowercase(Locale.ROOT).let { p -> "/download/" in p || p.endsWith("/download") || "/downloads/" in p || p.endsWith("/downloads") } }
    val visibleSongs = when {
        page == "Favoritos" -> songs.filter { it.uri in favorites }
        page == "Descargas" -> downloadedSongs
        page.startsWith("playlist:") -> songs.filter { it.uri in (selectedPlaylist?.songs ?: emptyList()) }
        page == "Buscar" -> songs.filter { it.title.contains(search,true) || it.artist.contains(search,true) || it.album.contains(search,true) }
        else -> songs
    }

    BackHandler {
        when {
            showPlayer -> showPlayer=false
            showQueue -> showQueue=false
            menuSong != null -> menuSong=null
            showAddSongsDialog -> showAddSongsDialog=false
            createDialog -> { createDialog=false; addSong=null }
            editTarget != null -> editTarget=null
            showTimer -> showTimer=false
            settingsSection != null -> settingsSection=null
            page.startsWith("playlist:") || page=="Descargas" || page=="Favoritos" -> page="Biblioteca"
            page=="Buscar" || page=="Biblioteca" || page=="Ajustes" -> {page="Inicio";settingsSection=null}
            else -> {page="Inicio";search=""}
        }
    }
    MaterialTheme(colorScheme = if (dark) darkColorScheme(primary=AccentBlue, background=bg, surface=surface) else lightColorScheme(primary=AccentBlue, background=bg, surface=surface)) {
        Column(Modifier.fillMaxSize().background(bg)) {
            Row(Modifier.fillMaxWidth().padding(horizontal=18.dp, vertical=14.dp), verticalAlignment=Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(AccentBlue), contentAlignment=Alignment.Center) { Icon(Icons.Default.GraphicEq, null, tint=Color.Black) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) { Text("NEGATIVE", color=secondary, fontSize=10.sp, letterSpacing=2.sp, fontWeight=FontWeight.Bold); Text("Music", color=fg, fontSize=24.sp, fontWeight=FontWeight.ExtraBold) }
                IconButton(onClick={page="Buscar"}) { Icon(Icons.Default.Search, "Buscar", tint=fg) }
                IconButton(onClick={page="Ajustes"}) { Icon(Icons.Default.Settings, "Ajustes", tint=fg) }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                page == "Ajustes" -> SettingsPage(theme, settingsSection, { settingsSection = it }, { theme=it; prefs.edit().putString("theme",it).apply() }, crossfade, { crossfade=it; prefs.edit().putInt("crossfade",it.toInt()).apply() }, mono, { mono=it; prefs.edit().putBoolean("mono",it).apply() }, normalize, { normalize=it; prefs.edit().putBoolean("normalize",it).apply(); controller?.volume=if(it) .85f else 1f }, volume, { volume=it; prefs.edit().putString("volume",it).apply(); controller?.volume=when(it){"Bajo"->.55f;"Alto"->1f;else->.8f} }, eqOn, { eqOn=it; prefs.edit().putBoolean("eq",it).apply(); PlaybackAudioEffects.setEnabled(it) }, eqBands, { index,value -> val updated=eqBands.toMutableList(); updated[index]=value; eqBands=updated; prefs.edit().putInt("eqBand$index",value.toInt()).apply(); PlaybackAudioEffects.applyBands(updated.map{it.toInt()}) }, songs.size, songs.sumOf{it.size}, ctx.filesDir.walkTopDown().filter{it.isFile}.sumOf{it.length()}, ctx.cacheDir.walkTopDown().filter{it.isFile}.sumOf{it.length()}, { ctx.cacheDir.deleteRecursively(); ctx.cacheDir.mkdirs(); toast="Caché limpiada." }, { clearDataDialog=true })
                page == "Buscar" -> Column(Modifier.fillMaxSize()) {
                    OutlinedTextField(search,{search=it},Modifier.fillMaxWidth().padding(horizontal=16.dp),placeholder={Text("¿Qué quieres escuchar?")},leadingIcon={Icon(Icons.Default.Search,null)},singleLine=true)
                    SongRows(visibleSongs,favorites,fg,secondary,now?.uri,{play(visibleSongs,it);showPlayer=true},{menuSong=it},{favorite(it)},{queue(it)}, Modifier.weight(1f))
                }
                page == "Biblioteca" -> LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=16.dp)) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(horizontal=18.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)){Text("Tu biblioteca",color=fg,fontSize=27.sp,fontWeight=FontWeight.ExtraBold);Text("${playlists.size} playlists",color=secondary,fontSize=13.sp)}
                            Button(onClick={createDialog=true},colors=ButtonDefaults.buttonColors(containerColor=AccentBlue,contentColor=Color.White)){Icon(Icons.Default.Add,null);Spacer(Modifier.width(5.dp));Text("Crear")}
                        }
                    }
                    item { HomeRow("Descargas","${downloadedSongs.size} canciones descargadas",Icons.Default.Download,fg,secondary){page="Descargas"} }
                    item { HomeRow("Canciones favoritas","${favorites.size} canciones",Icons.Default.Favorite,fg,secondary){page="Favoritos"} }
                    if(playlists.isEmpty()) item { Text("Tus playlists aparecerán aquí cuando crees una.",Modifier.padding(22.dp),color=secondary) }
                    items(playlists,key={it.id}) { p -> HomeRow(p.name,"${p.songs.size} canciones",Icons.Default.QueueMusic,fg,secondary){page="playlist:"+p.id} }
                }
                page == "Descargas" -> Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
                        IconButton(onClick={page="Biblioteca"}){Icon(Icons.Default.ArrowBack,"Volver",tint=fg)}
                        Column(Modifier.weight(1f)){Text("Descargas",color=fg,fontSize=26.sp,fontWeight=FontWeight.ExtraBold);Text("${downloadedSongs.size} canciones",color=secondary,fontSize=12.sp)}
                        IconButton(onClick={scope.launch { loading=true; songs=withContext(Dispatchers.IO){scanMusic(ctx)}; loading=false }}){Icon(Icons.Default.Refresh,null,tint=fg)}
                    }
                    SongRows(downloadedSongs,favorites,fg,secondary,now?.uri,{play(downloadedSongs,it);showPlayer=true},{menuSong=it},{favorite(it)},{queue(it)},Modifier.weight(1f))
                }
                page.startsWith("playlist:") -> Column(Modifier.fillMaxSize()) {
                    Text(selectedPlaylist?.name ?: "Playlist",Modifier.padding(start=20.dp,end=20.dp,top=14.dp,bottom=4.dp),color=fg,fontSize=27.sp,fontWeight=FontWeight.ExtraBold)
                    Text(selectedPlaylist?.description.orEmpty(),Modifier.padding(horizontal=20.dp),color=secondary,maxLines=2,overflow=TextOverflow.Ellipsis)
                    Row(Modifier.fillMaxWidth().padding(horizontal=14.dp, vertical=10.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Button(onClick={if(visibleSongs.isNotEmpty())play(visibleSongs.shuffled()) else toast="Esta playlist aún no tiene canciones."},modifier=Modifier.weight(1f),colors=ButtonDefaults.buttonColors(containerColor=AccentBlue,contentColor=Color.White)){Icon(Icons.Default.PlayArrow,null);Spacer(Modifier.width(6.dp));Text("Reproducir")}
                        OutlinedButton(onClick={selectedSongUris=emptySet();addSongsSearch="";showAddSongsDialog=true},modifier=Modifier.weight(1f)){Icon(Icons.Default.Add,null);Spacer(Modifier.width(5.dp));Text("Añadir canción")}
                        IconButton(onClick={editTarget=selectedPlaylist}){Icon(Icons.Default.Edit,"Editar playlist",tint=fg)}
                    }
                    SongRows(visibleSongs,favorites,fg,secondary,now?.uri,{play(visibleSongs,it);showPlayer=true},{menuSong=it},{favorite(it)},{queue(it)}, Modifier.weight(1f))
                }
                page == "Favoritos" -> Column(Modifier.fillMaxSize()) { Text("Tus canciones favoritas",Modifier.padding(20.dp),color=fg,fontSize=26.sp,fontWeight=FontWeight.Bold); SongRows(songs.filter{it.uri in favorites},favorites,fg,secondary,now?.uri,{play(songs.filter{it.uri in favorites},it);showPlayer=true},{menuSong=it},{favorite(it)},{queue(it)}, Modifier.weight(1f)) }
                else -> LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=8.dp)) {
                    item { Column(Modifier.padding(horizontal=20.dp, vertical=10.dp)) { Text("Tu música, tu mundo.",color=fg,fontSize=28.sp,fontWeight=FontWeight.ExtraBold); Text(if(loading)"Buscando música…" else "${songs.size} canciones en este dispositivo",color=secondary) } }
                    item { Row(Modifier.fillMaxWidth().padding(16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        HomeTile("Reproducir todo","Tu biblioteca",Icons.Default.PlayArrow,Modifier.weight(1f)){play(songs)}
                        HomeTile("Modo aleatorio","Mezcla tu música",Icons.Default.Shuffle,Modifier.weight(1f)){play(songs.shuffled())}
                    } }
                    item { Text("Tu biblioteca",Modifier.padding(start=20.dp,top=14.dp,bottom=6.dp),color=fg,fontSize=19.sp,fontWeight=FontWeight.Bold) }
                    item { HomeRow("Buscar canciones","${songs.size} canciones en el dispositivo",Icons.Default.LibraryMusic,fg,secondary){page="Buscar"} }
                    item { HomeRow("Canciones favoritas","${favorites.size} canciones",Icons.Default.Favorite,fg,secondary){page="Favoritos"} }
                    item { Text("Tus playlists",Modifier.padding(start=20.dp,top=18.dp,bottom=6.dp),color=fg,fontSize=19.sp,fontWeight=FontWeight.Bold) }
                    item { HomeRow("Crear playlist","Organiza tu música",Icons.Default.Add,fg,secondary){createDialog=true} }
                    items(playlists) { p -> HomeRow(p.name,"${p.songs.size} canciones",Icons.Default.QueueMusic,fg,secondary){page="playlist:"+p.id} }
                }
            }
            }
            if (now != null) {
                Row(Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=5.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF102C4A)).clickable{showPlayer=true}.padding(8.dp),verticalAlignment=Alignment.CenterVertically) {
                    Icon(Icons.Default.Album,null,tint=AccentBlue,modifier=Modifier.size(38.dp))
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) { Text(now!!.title,color=Color.White,fontWeight=FontWeight.SemiBold,maxLines=1); Text(now!!.artist,color=Color.LightGray,fontSize=11.sp,maxLines=1) }
                    IconButton(onClick={if(controller?.isPlaying==true)controller?.pause() else controller?.play()}){Icon(if(playing)Icons.Default.Pause else Icons.Default.PlayArrow,null,tint=Color.White)}
                    IconButton(onClick={controller?.seekToNextMediaItem()}){Icon(Icons.Default.SkipNext,null,tint=Color.White)}
                    IconButton(onClick={showQueue=true}){Icon(Icons.Default.QueueMusic,"Fila",tint=Color.White)}
                    IconButton(onClick={outputNames=try { val am=ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager; am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).map{it.productName.toString()} } catch (_:Exception){emptyList()};outputDialog=true}){Icon(Icons.Default.Speaker,null,tint=Color.White)}
                }
                Slider(value=if(duration>0)(position.toFloat()/duration).coerceIn(0f,1f) else 0f,onValueChange={controller?.seekTo((it*duration).toLong())},modifier=Modifier.fillMaxWidth().height(14.dp),colors=SliderDefaults.colors(thumbColor=AccentBlue,activeTrackColor=AccentBlue))
                Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),horizontalArrangement=Arrangement.SpaceBetween) { Text(time(position),color=secondary,fontSize=10.sp); Text(time(duration),color=secondary,fontSize=10.sp) }
            }
            NavigationBar(containerColor=surface,contentColor=fg) {
                listOf(Triple("Inicio",Icons.Default.Home,"Inicio"),Triple("Buscar",Icons.Default.Search,"Buscar"),Triple("Biblioteca",Icons.Default.LibraryMusic,"Biblioteca"),Triple("Ajustes",Icons.Default.Settings,"Ajustes")).forEach { (label,icon,target) ->
                    NavigationBarItem(selected=page==target || (target=="Biblioteca"&&(page=="Descargas"||page=="Favoritos"||page.startsWith("playlist:"))),onClick={page=target;search="";if(target=="Ajustes")settingsSection=null},icon={Icon(icon,null)},label={Text(label,fontSize=10.sp)},colors=NavigationBarItemDefaults.colors(selectedIconColor=AccentBlue,indicatorColor=AccentBlue.copy(alpha=.14f),selectedTextColor=AccentBlue,unselectedTextColor=secondary))
                }
            }
        }
    }
    if (toast.isNotBlank()) AlertDialog(onDismissRequest={toast=""},text={Text(toast)},confirmButton={TextButton(onClick={toast=""}){Text("OK")}})
    if (createDialog) PlaylistDialog("Crear playlist","","","",{createDialog=false}) { n,d,c -> val p=Playlist(System.currentTimeMillis().toString(),n,d,c,addSong?.let{listOf(it.uri)}?: emptyList());saveLists(playlists+p);addSong=null;createDialog=false;page="playlist:"+p.id }
    if (showAddSongsDialog) {
        val currentPlaylist = playlists.firstOrNull { page == "playlist:" + it.id }
        val candidates = songs.filter { it.title.contains(addSongsSearch, true) || it.artist.contains(addSongsSearch, true) || it.album.contains(addSongsSearch, true) }
        androidx.compose.ui.window.Dialog(onDismissRequest = { showAddSongsDialog = false }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(modifier = Modifier.fillMaxWidth(0.94f).heightIn(max = 680.dp), shape = RoundedCornerShape(26.dp), color = Panel, tonalElevation = 8.dp) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(AccentBlue.copy(alpha=.18f)), contentAlignment = Alignment.Center) { Icon(Icons.Default.PlaylistAdd, null, tint=AccentCyan) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) { Text("Añadir canciones", color=Color.White, fontSize=21.sp, fontWeight=FontWeight.Bold); Text(currentPlaylist?.name ?: "Playlist", color=Gray, fontSize=12.sp) }
                        IconButton(onClick={showAddSongsDialog=false}) { Icon(Icons.Default.Close, null, tint=Gray) }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(value=addSongsSearch,onValueChange={addSongsSearch=it},modifier=Modifier.fillMaxWidth(),singleLine=true,placeholder={Text("Buscar en tu música")},leadingIcon={Icon(Icons.Default.Search,null)},shape=RoundedCornerShape(14.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(if(candidates.isEmpty()) "No se encontraron canciones." else "${selectedSongUris.size} seleccionadas · ${candidates.size} disponibles", color=Gray, fontSize=12.sp)
                    Spacer(Modifier.height(6.dp))
                    if (candidates.isEmpty()) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment=Alignment.Center) { Text(if(songs.isEmpty()) "Primero actualiza la biblioteca para detectar música." else "Prueba con otro título o artista.", color=Gray) }
                    } else {
                        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement=Arrangement.spacedBy(3.dp)) {
                            items(candidates, key={it.uri}) { song ->
                                val alreadyAdded = currentPlaylist?.songs?.contains(song.uri) == true
                                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled=!alreadyAdded) { selectedSongUris = if(song.uri in selectedSongUris) selectedSongUris-song.uri else selectedSongUris+song.uri }.padding(horizontal=7.dp, vertical=8.dp), verticalAlignment=Alignment.CenterVertically) {
                                    Box(Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF102D4A)), contentAlignment=Alignment.Center) { Icon(Icons.Default.MusicNote,null,tint=AccentBlue) }
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) { Text(song.title,color=if(alreadyAdded) Gray else Color.White,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis); Text(song.artist,color=Gray,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis) }
                                    if(alreadyAdded) Text("Añadida", color=AccentCyan, fontSize=11.sp) else Checkbox(checked=song.uri in selectedSongUris,onCheckedChange={checked->selectedSongUris=if(checked)selectedSongUris+song.uri else selectedSongUris-song.uri},colors=CheckboxDefaults.colors(checkedColor=AccentBlue))
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick={showAddSongsDialog=false},modifier=Modifier.weight(1f)) { Text("Cancelar") }
                        Button(onClick={
                            if(currentPlaylist!=null) {
                                val merged=(currentPlaylist.songs+selectedSongUris).distinct()
                                saveLists(playlists.map{if(it.id==currentPlaylist.id)it.copy(songs=merged)else it})
                                toast=if(selectedSongUris.isEmpty()) "No seleccionaste canciones nuevas." else "Se añadieron ${merged.size-currentPlaylist.songs.size} canciones."
                            }
                            showAddSongsDialog=false
                        },enabled=selectedSongUris.isNotEmpty(),modifier=Modifier.weight(1f),colors=ButtonDefaults.buttonColors(containerColor=AccentBlue,contentColor=Color.White)) { Text("Añadir (${selectedSongUris.size})") }
                    }
                }
            }
        }
    }
    editTarget?.let { p -> PlaylistDialog("Editar playlist",p.name,p.description,p.cover,{editTarget=null}) { n,d,c -> saveLists(playlists.map{if(it.id==p.id)it.copy(name=n,description=d,cover=c)else it});editTarget=null } }
    menuSong?.let { s -> androidx.compose.material3.ModalBottomSheet(onDismissRequest={menuSong=null},containerColor=surface,contentColor=fg) {
        Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(9.dp)).background(Color(0xFF102D4A)),contentAlignment=Alignment.Center){Icon(Icons.Default.Album,null,tint=AccentBlue)}
            Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text(s.title,color=fg,fontSize=13.sp,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis);Text(s.artist,color=secondary,fontSize=11.sp,maxLines=1,overflow=TextOverflow.Ellipsis)}
        }
        HorizontalDivider(color=AccentCyan.copy(alpha=.18f))
        BottomAction("Añadir a la fila",Icons.Default.PlaylistAdd,fg){queue(s);menuSong=null}
        BottomAction(if(s.uri in favorites)"Quitar de favoritos" else "Añadir a favoritos",Icons.Default.Favorite,fg){favorite(s);menuSong=null}
        BottomAction("Crear playlist con esta canción",Icons.Default.Add,fg){addSong=s;createDialog=true;menuSong=null}
        playlists.forEach { p -> BottomAction("Añadir a ${p.name}",Icons.Default.QueueMusic,fg){saveLists(playlists.map{if(it.id==p.id&&s.uri !in it.songs)it.copy(songs=it.songs+s.uri)else it});menuSong=null;toast="Añadida a ${p.name}"} }
        BottomAction("Ir a la fila de reproducción",Icons.Default.QueueMusic,fg){showQueue=true;menuSong=null}
        BottomAction("Apagado automático",Icons.Default.Timer,fg){showTimer=true;menuSong=null}
        Spacer(Modifier.height(20.dp))
    } }
    if (showQueue) androidx.compose.material3.ModalBottomSheet(onDismissRequest={showQueue=false},containerColor=surface,contentColor=fg) {
        Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically){Text("Fila de reproducción",fontSize=23.sp,fontWeight=FontWeight.ExtraBold,color=fg,modifier=Modifier.weight(1f));TextButton(onClick={controller?.clearMediaItems();showQueue=false}){Text("Vaciar")}}
        val p=controller
        if(p==null||p.mediaItemCount==0) Box(Modifier.fillMaxWidth().height(180.dp),contentAlignment=Alignment.Center){Text("La fila está vacía.",color=secondary)}
        else LazyColumn(Modifier.fillMaxWidth().heightIn(max=520.dp).padding(bottom=20.dp)){items((0 until p.mediaItemCount).toList(),key={it}){i->val mi=p.getMediaItemAt(i);val song=songs.firstOrNull{it.uri==mi.localConfiguration?.uri?.toString()};Row(Modifier.fillMaxWidth().clickable{p.seekTo(i,0);p.play();showQueue=false;showPlayer=true}.padding(horizontal=18.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF102D4A)),contentAlignment=Alignment.Center){Icon(Icons.Default.Album,null,tint=AccentBlue)};Spacer(Modifier.width(10.dp));Column(Modifier.weight(1f)){Text(song?.title?:mi.mediaMetadata.title?.toString().orEmpty(),color=if(i==p.currentMediaItemIndex)AccentBlue else fg,maxLines=1);Text(song?.artist?:mi.mediaMetadata.artist?.toString().orEmpty(),color=secondary,fontSize=12.sp,maxLines=1)};IconButton(onClick={p.removeMediaItem(i)}){Icon(Icons.Default.Close,"Quitar",tint=secondary)}}}}
    }
    if (showPlayer && now != null) androidx.compose.ui.window.Dialog(onDismissRequest={showPlayer=false},properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize(),color=bg) {
            Column(Modifier.fillMaxSize().padding(horizontal=24.dp, vertical=18.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){IconButton(onClick={showPlayer=false}){Icon(Icons.Default.KeyboardArrowDown,"Minimizar",tint=fg,modifier=Modifier.size(30.dp))};Spacer(Modifier.weight(1f));Text("REPRODUCIENDO",color=secondary,fontSize=10.sp,letterSpacing=2.sp,fontWeight=FontWeight.Bold);Spacer(Modifier.weight(1f));IconButton(onClick={showQueue=true;showPlayer=false}){Icon(Icons.Default.QueueMusic,"Fila",tint=fg)}}
                Spacer(Modifier.weight(.6f))
                Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(22.dp)).background(Brush.linearGradient(listOf(Color(0xFF123C67),Color(0xFF071018)))),contentAlignment=Alignment.Center){Icon(Icons.Default.Album,null,tint=AccentCyan,modifier=Modifier.size(100.dp))}
                Spacer(Modifier.height(30.dp))
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(now!!.title,color=fg,fontSize=23.sp,fontWeight=FontWeight.ExtraBold,maxLines=2,overflow=TextOverflow.Ellipsis);Text(now!!.artist,color=secondary,fontSize=15.sp,maxLines=1)};IconButton(onClick={favorite(now!!);}){Icon(Icons.Default.Favorite,null,tint=if(now!!.uri in favorites)AccentBlue else secondary)}}
                Spacer(Modifier.height(20.dp))
                Slider(value=if(duration>0)(position.toFloat()/duration).coerceIn(0f,1f) else 0f,onValueChange={controller?.seekTo((it*duration).toLong())},colors=SliderDefaults.colors(thumbColor=AccentBlue,activeTrackColor=AccentBlue))
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(time(position),color=secondary,fontSize=11.sp);Text(time(duration),color=secondary,fontSize=11.sp)}
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly,verticalAlignment=Alignment.CenterVertically){IconButton(onClick={controller?.shuffleModeEnabled=!(controller?.shuffleModeEnabled?:false)}){Icon(Icons.Default.Shuffle,null,tint=if(shuffle)AccentBlue else secondary)};IconButton(onClick={controller?.seekToPreviousMediaItem()}){Icon(Icons.Default.SkipPrevious,null,tint=fg,modifier=Modifier.size(34.dp))};FilledIconButton(onClick={if(playing)controller?.pause() else controller?.play()},modifier=Modifier.size(70.dp),colors=IconButtonDefaults.filledIconButtonColors(containerColor=AccentBlue,contentColor=Color.White)){Icon(if(playing)Icons.Default.Pause else Icons.Default.PlayArrow,null,modifier=Modifier.size(36.dp))};IconButton(onClick={controller?.seekToNextMediaItem()}){Icon(Icons.Default.SkipNext,null,tint=fg,modifier=Modifier.size(34.dp))};IconButton(onClick={controller?.repeatMode=when(repeat){Player.REPEAT_MODE_OFF->Player.REPEAT_MODE_ALL;Player.REPEAT_MODE_ALL->Player.REPEAT_MODE_ONE;else->Player.REPEAT_MODE_OFF}}){Icon(Icons.Default.Repeat,null,tint=if(repeat!=Player.REPEAT_MODE_OFF)AccentBlue else secondary)}}
                Spacer(Modifier.weight(.8f))
            }
        }
    }
    if (showTimer) AlertDialog(onDismissRequest={showTimer=false},title={Text("Apagado automático")},text={Column{Text("Detener después de $timerMins minutos");Slider(value=timerMins.toFloat(),onValueChange={timerMins=it.toInt()},valueRange=5f..180f,steps=34)}},confirmButton={TextButton(onClick={deadline=System.currentTimeMillis()+timerMins*60000L;showTimer=false;toast="Temporizador activado."}){Text("Activar")}},dismissButton={TextButton(onClick={deadline=0;showTimer=false}){Text("Cancelar")}})
    if (outputDialog) AlertDialog(onDismissRequest={outputDialog=false},title={Text("Salidas de audio detectadas")},text={Column{if(outputNames.isEmpty())Text("No se detectaron salidas disponibles.") else outputNames.distinct().forEach{Text("• $it",Modifier.padding(vertical=3.dp))};Text("Para cambiar de salida, utiliza también el selector de audio de Android.",fontSize=12.sp,color=Gray)}},confirmButton={TextButton(onClick={outputDialog=false}){Text("Cerrar")}})
    if (clearDataDialog) AlertDialog(onDismissRequest={clearDataDialog=false},title={Text("Limpiar almacenamiento")},text={Text("Se borrarán playlists, favoritos y preferencias. Las canciones originales del teléfono no se eliminarán.")},confirmButton={TextButton(onClick={prefs.edit().clear().apply();saveLists(emptyList());saveFavorites(emptySet());theme="dark";crossfade=0f;mono=false;normalize=false;volume="Normal";eqOn=false;(0..4).forEach{prefs.edit().putInt("eqBand$it",0).apply()};eqBands=listOf(0f,0f,0f,0f,0f);PlaybackAudioEffects.applyBands(listOf(0,0,0,0,0));PlaybackAudioEffects.setEnabled(false);clearDataDialog=false;toast="Datos de la app limpiados."}){Text("Limpiar datos")}},dismissButton={TextButton(onClick={clearDataDialog=false}){Text("Cancelar")}})
}

@Composable private fun BottomAction(label:String,icon:androidx.compose.ui.graphics.vector.ImageVector,fg:Color,onClick:()->Unit){Row(Modifier.fillMaxWidth().clickable(onClick=onClick).padding(horizontal=22.dp,vertical=13.dp),verticalAlignment=Alignment.CenterVertically){Icon(icon,null,tint=fg);Spacer(Modifier.width(18.dp));Text(label,color=fg,fontSize=15.sp)}}

private fun readPlaylists(prefs: android.content.SharedPreferences): List<Playlist> = try {
    val a=JSONArray(prefs.getString("playlists","[]")?: "[]")
    (0 until a.length()).map { i -> val o=a.getJSONObject(i);val ss=o.optJSONArray("songs")?:JSONArray();Playlist(o.optString("id"),o.optString("name"),o.optString("description"),o.optString("cover"),(0 until ss.length()).map{ss.getString(it)}) }
} catch (_:Exception){emptyList()}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable private fun SongRows(songs:List<Song>,favorites:Set<String>,fg:Color,secondary:Color,current:String?,play:(Song)->Unit,menu:(Song)->Unit,favorite:(Song)->Unit,enqueue:(Song)->Unit,modifier: Modifier = Modifier.fillMaxWidth()) {
    if(songs.isEmpty()) Box(Modifier.fillMaxWidth().padding(28.dp),contentAlignment=Alignment.Center){Text("Todavía no hay canciones aquí.",color=secondary)}
    else LazyColumn(modifier,contentPadding=PaddingValues(bottom=12.dp)){items(songs,key={it.uri}){s->Row(Modifier.fillMaxWidth().combinedClickable(onClick={play(s)},onLongClick={menu(s)}).pointerInput(s.uri){var drag=0f;detectHorizontalDragGestures(onHorizontalDrag={change,amount->drag+=amount;change.consume()},onDragEnd={if(drag>72f)enqueue(s);drag=0f},onDragCancel={drag=0f})}.padding(horizontal=16.dp,vertical=7.dp),verticalAlignment=Alignment.CenterVertically){
        Box(Modifier.size(50.dp).clip(RoundedCornerShape(8.dp)).background(Brush.linearGradient(listOf(Color(0xFF123C67),Color(0xFF252525)))),contentAlignment=Alignment.Center){Icon(Icons.Default.Album,null,tint=AccentBlue,modifier=Modifier.size(28.dp))}
        Spacer(Modifier.width(11.dp));Column(Modifier.weight(1f)){Text(s.title,color=if(s.uri==current)AccentBlue else fg,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis);Text("${s.artist} · ${time(s.duration)}",color=secondary,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis)}
        if(s.uri in favorites)Icon(Icons.Default.Favorite,null,tint=AccentBlue,modifier=Modifier.size(16.dp))
        IconButton(onClick={menu(s)}){Icon(Icons.Default.MoreVert,"Más opciones",tint=secondary)}
    }}}
}
@Composable private fun HomeTile(title:String,sub:String,icon:androidx.compose.ui.graphics.vector.ImageVector,modifier:Modifier,onClick:()->Unit){
    Card(modifier.clickable(onClick=onClick),colors=CardDefaults.cardColors(containerColor=Panel),shape=RoundedCornerShape(16.dp)){Column(Modifier.padding(15.dp)){Icon(icon,null,tint=AccentBlue,modifier=Modifier.size(28.dp));Spacer(Modifier.height(14.dp));Text(title,color=Color.White,fontWeight=FontWeight.Bold);Text(sub,color=Gray,fontSize=11.sp)}}
}
@Composable private fun HomeRow(title:String,sub:String,icon:androidx.compose.ui.graphics.vector.ImageVector,fg:Color,secondary:Color,onClick:()->Unit){
    Row(Modifier.fillMaxWidth().clickable(onClick=onClick).padding(horizontal=20.dp,vertical=9.dp),verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF102D4A)),contentAlignment=Alignment.Center){Icon(icon,null,tint=AccentBlue)};Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text(title,color=fg,fontWeight=FontWeight.SemiBold);Text(sub,color=secondary,fontSize=12.sp)};Icon(Icons.Default.ChevronRight,null,tint=secondary)}
}
@Composable
private fun PlaylistDialog(title:String,initialName:String,initialDesc:String,initialCover:String,onDismiss:()->Unit,onSave:(String,String,String)->Unit) {
    var name by remember { mutableStateOf(initialName) }
    var desc by remember { mutableStateOf(initialDesc) }
    var cover by remember { mutableStateOf(initialCover) }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){it?.let{uri->cover=uri.toString()}}
    androidx.compose.ui.window.Dialog(onDismissRequest=onDismiss,properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(modifier=Modifier.fillMaxWidth(0.92f).heightIn(max=640.dp),shape=RoundedCornerShape(26.dp),color=Panel,tonalElevation=10.dp) {
            Column(Modifier.padding(22.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(15.dp)).background(AccentBlue.copy(alpha=.18f)),contentAlignment=Alignment.Center) {
                        Icon(Icons.Default.QueueMusic,null,tint=AccentCyan,modifier=Modifier.size(27.dp))
                    }
                    Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title,color=Color.White,fontSize=22.sp,fontWeight=FontWeight.ExtraBold)
                        Text(if(initialName.isBlank()) "Crea un espacio para tu música" else "Personaliza tu colección",color=Gray,fontSize=12.sp)
                    }
                    IconButton(onClick=onDismiss){Icon(Icons.Default.Close,"Cerrar",tint=Gray)}
                }
                Box(Modifier.fillMaxWidth().height(112.dp).clip(RoundedCornerShape(18.dp)).background(Brush.linearGradient(listOf(Color(0xFF102D4A),Color(0xFF10202C)))),contentAlignment=Alignment.Center) {
                    Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(5.dp)) {
                        Icon(if(cover.isBlank()) Icons.Default.MusicNote else Icons.Default.Image,null,tint=AccentCyan,modifier=Modifier.size(35.dp))
                        Text(if(cover.isBlank()) "Tu playlist" else "Portada seleccionada",color=Color.White,fontWeight=FontWeight.SemiBold)
                        Text("Puedes cambiar la portada cuando quieras",color=Gray,fontSize=11.sp)
                    }
                }
                OutlinedTextField(value=name,onValueChange={name=it},modifier=Modifier.fillMaxWidth(),label={Text("Nombre de la playlist")},placeholder={Text("Por ejemplo: Favoritas de noche")},singleLine=true,shape=RoundedCornerShape(14.dp),leadingIcon={Icon(Icons.Default.Edit,null)},colors=OutlinedTextFieldDefaults.colors(focusedBorderColor=AccentBlue,focusedLabelColor=AccentBlue,cursorColor=AccentBlue))
                OutlinedTextField(value=desc,onValueChange={desc=it},modifier=Modifier.fillMaxWidth(),label={Text("Descripción (opcional)")},placeholder={Text("¿Qué canciones reúne?")},minLines=2,maxLines=3,shape=RoundedCornerShape(14.dp),colors=OutlinedTextFieldDefaults.colors(focusedBorderColor=AccentBlue,focusedLabelColor=AccentBlue,cursorColor=AccentBlue))
                OutlinedButton(onClick={picker.launch("image/*")},modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp)) {
                    Icon(Icons.Default.Image,null);Spacer(Modifier.width(8.dp));Text(if(cover.isBlank()) "Elegir imagen de portada" else "Cambiar imagen de portada")
                }
                Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick=onDismiss,modifier=Modifier.weight(1f),shape=RoundedCornerShape(14.dp)){Text("Cancelar")}
                    Button(onClick={onSave(name.trim(),desc.trim(),cover)},enabled=name.isNotBlank(),modifier=Modifier.weight(1f),shape=RoundedCornerShape(14.dp),colors=ButtonDefaults.buttonColors(containerColor=AccentBlue,contentColor=Color.White)){Icon(Icons.Default.Check,null);Spacer(Modifier.width(6.dp));Text("Guardar")}
                }
            }
        }
    }
}

@Composable
private fun SettingsPage(
    theme: String, section: String?, onSection: (String?) -> Unit, onTheme: (String) -> Unit,
    crossfade: Float, onCrossfade: (Float) -> Unit, mono: Boolean, onMono: (Boolean) -> Unit,
    normalize: Boolean, onNormalize: (Boolean) -> Unit, volume: String, onVolume: (String) -> Unit,
    eq: Boolean, onEq: (Boolean) -> Unit, eqBands: List<Float>, onBand: (Int, Float) -> Unit, songCount: Int, songBytes: Long, appBytes: Long,
    cacheBytes: Long, onCache: () -> Unit, onClearData: () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (theme) { "light" -> false; "system" -> systemDark; else -> true }
    val fg = if (dark) Color.White else Color(0xFF111820)
    val sec = if (dark) Color(0xFFB3C0CE) else Color(0xFF596675)
    val card = if (dark) Panel else Color.White
    val storage = remember {
        runCatching {
            val stat = StatFs(Environment.getDataDirectory().path)
            val totalBytes = stat.blockCountLong * stat.blockSizeLong
            val freeBytes = stat.availableBlocksLong * stat.blockSizeLong
            Triple(totalBytes, freeBytes, (totalBytes - freeBytes).coerceAtLeast(0L))
        }.getOrElse { Triple(0L, 0L, 0L) }
    }
    val total = storage.first
    val free = storage.second
    val used = storage.third
    val usage = if (total > 0L) (used.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (section != null) IconButton(onClick = { onSection(null) }) { Icon(Icons.Default.ArrowBack, "Volver", tint = fg) }
                Column(Modifier.weight(1f)) {
                    Text(if (section == null) "Configuración" else section, color = fg, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                    Text(if (section == null) "Personaliza NegativeMusic" else "Ajustes de " + section, color = sec, fontSize = 13.sp)
                }
            }
        }
        if (section == null) {
            item { SettingsCategory("Apariencia", "Tema y aspecto de la aplicación", Icons.Default.Palette, fg, sec, card) { onSection("Apariencia") } }
            item { SettingsCategory("Reproducción", "Crossfade, audio mono, volumen y ecualizador", Icons.Default.GraphicEq, fg, sec, card) { onSection("Reproducción") } }
            item { SettingsCategory("Almacenamiento", "Espacio del dispositivo, música y caché", Icons.Default.Storage, fg, sec, card) { onSection("Almacenamiento") } }
            item { SettingsCategory("Datos y privacidad", "Borrar caché o datos locales", Icons.Default.Security, fg, sec, card) { onSection("Datos y privacidad") } }
            item { SettingsCategory("Acerca de", "Versión y detalles de NegativeMusic", Icons.Default.Info, fg, sec, card) { onSection("Acerca de") } }
        } else when (section) {
            "Apariencia" -> item {
                Card(colors = CardDefaults.cardColors(containerColor = card)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Tema de la aplicación", color = fg, fontWeight = FontWeight.Bold)
                        listOf("Sistema" to "system", "Oscuro" to "dark", "Claro" to "light").forEach { (label, key) ->
                            Row(Modifier.fillMaxWidth().clickable { onTheme(key) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(label, color = fg, modifier = Modifier.weight(1f))
                                RadioButton(selected = theme == key, onClick = { onTheme(key) }, colors = RadioButtonDefaults.colors(selectedColor = AccentBlue))
                            }
                        }
                        HorizontalDivider(color = AccentCyan.copy(alpha = .25f))
                        Text("Identidad NegativeStudios", color = fg, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)).background(AccentBlue))
                            Box(Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)).background(AccentCyan))
                            Text("Azul y cian", color = sec)
                        }
                    }
                }
            }
            "Reproducción" -> item {
                Card(colors = CardDefaults.cardColors(containerColor = card)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Crossfade: " + crossfade.toInt() + " s", color = fg, fontWeight = FontWeight.SemiBold)
                        Slider(value = crossfade, onValueChange = onCrossfade, valueRange = 0f..12f, steps = 11, colors = SliderDefaults.colors(thumbColor = AccentBlue, activeTrackColor = AccentBlue))
                        Text("La preferencia se guarda, pero la mezcla cruzada real aún necesita integrarse en el motor de reproducción.", color = sec, fontSize = 12.sp)
                        HorizontalDivider(color = AccentCyan.copy(alpha = .2f))
                        SettingsSwitch("Audio mono", "Guarda la preferencia; el procesamiento mono todavía no está aplicado por la app.", mono, onMono, fg, sec)
                        SettingsSwitch("Normalización de volumen", "El ajuste actual cambia el nivel de salida, no analiza la sonoridad de cada canción.", normalize, onNormalize, fg, sec)
                        Text("Nivel de volumen", color = fg, fontWeight = FontWeight.SemiBold)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("Bajo", "Normal", "Alto").forEach { FilterChip(selected = volume == it, onClick = { onVolume(it) }, label = { Text(it) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = AccentBlue.copy(alpha = .2f), selectedLabelColor = fg)) }
                        }
                        SettingsSwitch("Ecualizador", "Ajusta el sonido durante la reproducción.", eq, onEq, fg, sec)
                        if (eq) {
                            Text("Ecualizador", color=fg, fontSize=18.sp, fontWeight=FontWeight.Bold)
                            Row(Modifier.fillMaxWidth().height(180.dp),horizontalArrangement=Arrangement.SpaceEvenly,verticalAlignment=Alignment.CenterVertically) {
                                val frequencies=listOf("60","230","910","3.6k","14k")
                                eqBands.forEachIndexed { index,value ->
                                    Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
                                        Text("%+d".format(value.toInt()),color=AccentBlue,fontSize=10.sp,fontWeight=FontWeight.SemiBold)
                                        Box(Modifier.height(132.dp).fillMaxWidth(),contentAlignment=Alignment.Center) {
                                            Slider(value=value,onValueChange={onBand(index,it)},valueRange=-15f..15f,steps=29,modifier=Modifier.width(130.dp).height(30.dp).rotate(-90f),colors=SliderDefaults.colors(thumbColor=AccentBlue,activeTrackColor=AccentBlue,inactiveTrackColor=AccentCyan.copy(alpha=.18f)))
                                        }
                                        Text(frequencies[index],color=sec,fontSize=10.sp)
                                    }
                                }
                            }
                            Text("Ajustes aplicados al efecto Equalizer de Android cuando el dispositivo lo admite.",color=sec,fontSize=11.sp)
                        }
                    }
                }
            }
            "Almacenamiento" -> item {
                Card(colors = CardDefaults.cardColors(containerColor = card)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Almacenamiento del dispositivo", color = fg, fontWeight = FontWeight.Bold)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Usado", color = sec, fontSize = 13.sp)
                            Text(if (total > 0) bytes(used) else "No disponible", color = fg, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        }
                        LinearProgressIndicator(progress = { usage }, modifier = Modifier.fillMaxWidth().height(9.dp).clip(RoundedCornerShape(8.dp)), color = AccentBlue, trackColor = AccentCyan.copy(alpha = .16f))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(if (total > 0) (usage * 100).toInt().toString() + "% utilizado" else "Sin datos", color = AccentBlue, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text("Disponible: " + if (total > 0) bytes(free) else "—", color = sec, fontSize = 12.sp)
                        }
                        Text("Capacidad total: " + if (total > 0) bytes(total) else "No disponible", color = sec, fontSize = 12.sp)
                        HorizontalDivider(color = AccentCyan.copy(alpha = .2f))
                        StorageRow("Canciones indexadas", songCount.toString(), fg, sec)
                        StorageRow("Tamaño de canciones", bytes(songBytes), fg, sec)
                        StorageRow("Datos internos de la app", bytes(appBytes), fg, sec)
                        StorageRow("Caché de la app", bytes(cacheBytes), fg, sec)
                        Text("La barra refleja el almacenamiento que Android expone para el volumen principal; puede incluir datos del sistema y otras aplicaciones.", color = sec, fontSize = 12.sp)
                        OutlinedButton(onClick = onCache, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.DeleteSweep, null); Spacer(Modifier.width(8.dp)); Text("Limpiar caché") }
                    }
                }
            }
            "Datos y privacidad" -> item {
                Card(colors = CardDefaults.cardColors(containerColor = card)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Administración de datos", color = fg, fontWeight = FontWeight.Bold)
                        Text("Limpiar caché elimina archivos temporales. Limpiar los datos locales restablece playlists, favoritos y preferencias; no borra las canciones originales.", color = sec, fontSize = 13.sp)
                        OutlinedButton(onClick = onCache, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.DeleteSweep, null); Spacer(Modifier.width(8.dp)); Text("Limpiar caché") }
                        Button(onClick = onClearData, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = AccentBlue, contentColor = Color.White)) { Icon(Icons.Default.DeleteForever, null); Spacer(Modifier.width(8.dp)); Text("Restablecer datos de la app") }
                    }
                }
            }
            "Acerca de" -> item {
                Card(colors = CardDefaults.cardColors(containerColor = card)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("NegativeMusic", color = fg, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("Versión 0.1.0", color = AccentBlue, fontWeight = FontWeight.SemiBold)
                        Text("Reproductor local de NegativeStudios.", color = sec)
                        Text("Interfaz azul y cian inspirada en el launcher NegativeStudios.", color = sec, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCategory(title: String, description: String, icon: androidx.compose.ui.graphics.vector.ImageVector, fg: Color, sec: Color, card: Color, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).clip(RoundedCornerShape(13.dp)).background(AccentBlue.copy(alpha = .15f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = AccentBlue) }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) { Text(title, color = fg, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(3.dp)); Text(description, color = sec, fontSize = 12.sp) }
            Icon(Icons.Default.ChevronRight, null, tint = sec)
        }
    }
}

@Composable
private fun SettingsSwitch(title: String, description: String, checked: Boolean, onChecked: (Boolean) -> Unit, fg: Color, sec: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) { Text(title, color = fg, fontWeight = FontWeight.SemiBold); Text(description, color = sec, fontSize = 12.sp) }
        Switch(checked = checked, onCheckedChange = onChecked, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AccentBlue))
    }
}

@Composable
private fun StorageRow(label: String, value: String, fg: Color, sec: Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, color = sec, fontSize = 13.sp); Text(value, color = fg, fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
}
