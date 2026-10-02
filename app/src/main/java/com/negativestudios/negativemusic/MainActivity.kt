package com.negativestudios.negativemusic

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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

private val Green = Color(0xFF1ED760)
private val Dark = Color(0xFF101010)
private val Panel = Color(0xFF202020)
private val Gray = Color(0xFFB3B3B3)

data class Song(val uri: String, val title: String, val artist: String, val album: String, val duration: Long, val size: Long, val albumId: Long)
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
    val cols = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.ALBUM_ID)
    val out = mutableListOf<Song>()
    context.contentResolver.query(base, cols, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use { c ->
        val id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val title = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val artist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val album = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
        val dur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
        val size = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
        val albumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
        while (c.moveToNext()) {
            val key = c.getLong(id)
            out += Song(Uri.withAppendedPath(base, key.toString()).toString(), c.getString(title) ?: "Canción desconocida", c.getString(artist)?.takeUnless { it == "<unknown>" } ?: "Artista desconocido", c.getString(album)?.takeUnless { it == "<unknown>" } ?: "Álbum desconocido", c.getLong(dur), c.getLong(size), c.getLong(albumId))
        }
    }
    return out
}
private fun time(ms: Long) = String.format(Locale.getDefault(), "%d:%02d", ms.coerceAtLeast(0) / 60000, (ms.coerceAtLeast(0) / 1000) % 60)
private fun bytes(n: Long): String = when { n < 1024 -> "$n B"; n < 1024L*1024 -> String.format(Locale.getDefault(), "%.1f KB", n/1024.0); n < 1024L*1024*1024 -> String.format(Locale.getDefault(), "%.1f MB", n/(1024.0*1024)); else -> String.format(Locale.getDefault(), "%.2f GB", n/(1024.0*1024*1024)) }

@Composable
private fun NegativeMusicApp() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("negative_music", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var songs by remember { mutableStateOf(emptyList<Song>()) }
    var loading by remember { mutableStateOf(true) }
    var page by remember { mutableStateOf("Inicio") }
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
    var editTarget by remember { mutableStateOf<Playlist?>(null) }
    var menuSong by remember { mutableStateOf<Song?>(null) }
    var addSong by remember { mutableStateOf<Song?>(null) }
    var showQueue by remember { mutableStateOf(false) }
    var showTimer by remember { mutableStateOf(false) }
    var timerMins by remember { mutableIntStateOf(30) }
    var deadline by remember { mutableLongStateOf(0L) }
    var crossfade by remember { mutableFloatStateOf(prefs.getInt("crossfade", 0).toFloat()) }
    var mono by remember { mutableStateOf(prefs.getBoolean("mono", false)) }
    var normalize by remember { mutableStateOf(prefs.getBoolean("normalize", false)) }
    var volume by remember { mutableStateOf(prefs.getString("volume", "Normal") ?: "Normal") }
    var eqOn by remember { mutableStateOf(prefs.getBoolean("eq", false)) }
    var bass by remember { mutableFloatStateOf(prefs.getInt("bass", 0).toFloat()) }
    var treble by remember { mutableFloatStateOf(prefs.getInt("treble", 0).toFloat()) }
    var clearDataDialog by remember { mutableStateOf(false) }
    var eqDialog by remember { mutableStateOf(false) }
    var outputDialog by remember { mutableStateOf(false) }
    var outputNames by remember { mutableStateOf(emptyList<String>()) }
    val dark = theme != "light"
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
        val p = controller ?: return@DisposableEffect
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
    val visibleSongs = when {
        page == "Favoritos" -> songs.filter { it.uri in favorites }
        page.startsWith("playlist:") -> songs.filter { it.uri in (selectedPlaylist?.songs ?: emptyList()) }
        page == "Buscar" -> songs.filter { it.title.contains(search,true) || it.artist.contains(search,true) || it.album.contains(search,true) }
        else -> songs
    }

    MaterialTheme(colorScheme = if (dark) darkColorScheme(primary=Green, background=bg, surface=surface) else lightColorScheme(primary=Color(0xFF168A45), background=bg, surface=surface)) {
        Column(Modifier.fillMaxSize().background(bg)) {
            Row(Modifier.fillMaxWidth().padding(horizontal=18.dp, vertical=14.dp), verticalAlignment=Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(Green), contentAlignment=Alignment.Center) { Icon(Icons.Default.GraphicEq, null, tint=Color.Black) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) { Text("NEGATIVE", color=secondary, fontSize=10.sp, letterSpacing=2.sp, fontWeight=FontWeight.Bold); Text("Music", color=fg, fontSize=24.sp, fontWeight=FontWeight.ExtraBold) }
                IconButton(onClick={page="Buscar"}) { Icon(Icons.Default.Search, "Buscar", tint=fg) }
                IconButton(onClick={page="Ajustes"}) { Icon(Icons.Default.Settings, "Ajustes", tint=fg) }
            }
            when {
                page == "Ajustes" -> SettingsPage(theme, { theme=it; prefs.edit().putString("theme",it).apply() }, crossfade, { crossfade=it; prefs.edit().putInt("crossfade",it.toInt()).apply() }, mono, { mono=it; prefs.edit().putBoolean("mono",it).apply() }, normalize, { normalize=it; prefs.edit().putBoolean("normalize",it).apply(); controller?.volume=if(it) .85f else 1f }, volume, { volume=it; prefs.edit().putString("volume",it).apply(); controller?.volume=when(it){"Bajo"->.55f;"Alto"->1f;else->.8f} }, eqOn, { eqOn=it; prefs.edit().putBoolean("eq",it).apply(); eqDialog=true }, songs.size, songs.sumOf{it.size}, ctx.filesDir.walkTopDown().filter{it.isFile}.sumOf{it.length()}, ctx.cacheDir.walkTopDown().filter{it.isFile}.sumOf{it.length()}, { ctx.cacheDir.deleteRecursively(); ctx.cacheDir.mkdirs(); toast="Caché limpiada." }, { clearDataDialog=true })
                page == "Buscar" -> Column {
                    OutlinedTextField(search,{search=it},Modifier.fillMaxWidth().padding(horizontal=16.dp),placeholder={Text("¿Qué quieres escuchar?")},leadingIcon={Icon(Icons.Default.Search,null)},singleLine=true)
                    SongRows(visibleSongs,favorites,fg,secondary,now?.uri,{play(visibleSongs,it)},{menuSong=it},{favorite(it)})
                }
                page == "Biblioteca" -> Column {
                    Row(Modifier.fillMaxWidth().padding(horizontal=14.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Button(onClick={play(songs.shuffled())},colors=ButtonDefaults.buttonColors(containerColor=Green,contentColor=Color.Black)){Icon(Icons.Default.Shuffle,null);Text("Aleatorio")}
                        OutlinedButton(onClick={createDialog=true}){Icon(Icons.Default.Add,null);Text("Playlist")}
                        IconButton(onClick={scope.launch { loading=true; songs=withContext(Dispatchers.IO){scanMusic(ctx)}; loading=false }}){Icon(Icons.Default.Refresh,null,tint=fg)}
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        FilterChip(page=="Biblioteca",{page="Biblioteca"},label={Text("Canciones")})
                        FilterChip(page=="Favoritos",{page="Favoritos"},label={Text("Favoritos")})
                    }
                    if (page=="Biblioteca") SongRows(songs,favorites,fg,secondary,now?.uri,{play(songs,it)},{menuSong=it},{favorite(it)})
                    else SongRows(songs.filter{it.uri in favorites},favorites,fg,secondary,now?.uri,{play(songs.filter{it.uri in favorites},it)},{menuSong=it},{favorite(it)})
                }
                page.startsWith("playlist:") -> Column {
                    Text(selectedPlaylist?.name ?: "Playlist",Modifier.padding(20.dp),color=fg,fontSize=27.sp,fontWeight=FontWeight.ExtraBold)
                    Text(selectedPlaylist?.description.orEmpty(),Modifier.padding(horizontal=20.dp),color=secondary)
                    Row(Modifier.padding(12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Button(onClick={play(visibleSongs.shuffled())},colors=ButtonDefaults.buttonColors(containerColor=Green,contentColor=Color.Black)){Icon(Icons.Default.PlayArrow,null);Text("Reproducir")}
                        OutlinedButton(onClick={editTarget=selectedPlaylist}){Icon(Icons.Default.Edit,null);Text("Editar")}
                    }
                    SongRows(visibleSongs,favorites,fg,secondary,now?.uri,{play(visibleSongs,it)},{menuSong=it},{favorite(it)})
                }
                page == "Favoritos" -> Column { Text("Tus canciones favoritas",Modifier.padding(20.dp),color=fg,fontSize=26.sp,fontWeight=FontWeight.Bold); SongRows(songs.filter{it.uri in favorites},favorites,fg,secondary,now?.uri,{play(songs.filter{it.uri in favorites},it)},{menuSong=it},{favorite(it)}) }
                else -> LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(bottom=8.dp)) {
                    item { Column(Modifier.padding(horizontal=20.dp, vertical=10.dp)) { Text("Tu música, tu mundo.",color=fg,fontSize=28.sp,fontWeight=FontWeight.ExtraBold); Text(if(loading)"Buscando música…" else "${songs.size} canciones en este dispositivo",color=secondary) } }
                    item { Row(Modifier.fillMaxWidth().padding(16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        HomeTile("Reproducir todo","Tu biblioteca",Icons.Default.PlayArrow,Modifier.weight(1f)){play(songs)}
                        HomeTile("Modo aleatorio","Mezcla tu música",Icons.Default.Shuffle,Modifier.weight(1f)){play(songs.shuffled())}
                    } }
                    item { Text("Tu biblioteca",Modifier.padding(start=20.dp,top=14.dp,bottom=6.dp),color=fg,fontSize=19.sp,fontWeight=FontWeight.Bold) }
                    item { HomeRow("Todas las canciones","${songs.size} canciones",Icons.Default.LibraryMusic,fg,secondary){page="Biblioteca"} }
                    item { HomeRow("Canciones favoritas","${favorites.size} canciones",Icons.Default.Favorite,fg,secondary){page="Favoritos"} }
                    item { Text("Tus playlists",Modifier.padding(start=20.dp,top=18.dp,bottom=6.dp),color=fg,fontSize=19.sp,fontWeight=FontWeight.Bold) }
                    item { HomeRow("Crear playlist","Organiza tu música",Icons.Default.Add,fg,secondary){createDialog=true} }
                    items(playlists) { p -> HomeRow(p.name,"${p.songs.size} canciones",Icons.Default.QueueMusic,fg,secondary){page="playlist:"+p.id} }
                }
            }
            if (now != null) {
                Row(Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=5.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF28533B)).clickable{showQueue=true}.padding(8.dp),verticalAlignment=Alignment.CenterVertically) {
                    Icon(Icons.Default.Album,null,tint=Green,modifier=Modifier.size(38.dp))
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) { Text(now!!.title,color=Color.White,fontWeight=FontWeight.SemiBold,maxLines=1); Text(now!!.artist,color=Color.LightGray,fontSize=11.sp,maxLines=1) }
                    IconButton(onClick={if(controller?.isPlaying==true)controller?.pause() else controller?.play()}){Icon(if(playing)Icons.Default.Pause else Icons.Default.PlayArrow,null,tint=Color.White)}
                    IconButton(onClick={controller?.seekToNextMediaItem()}){Icon(Icons.Default.SkipNext,null,tint=Color.White)}
                    IconButton(onClick={outputNames=try { val am=ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager; am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).map{it.productName.toString()} } catch (_:Exception){emptyList()};outputDialog=true}){Icon(Icons.Default.Speaker,null,tint=Color.White)}
                }
                Slider(value=if(duration>0)(position.toFloat()/duration).coerceIn(0f,1f) else 0f,onValueChange={controller?.seekTo((it*duration).toLong())},modifier=Modifier.fillMaxWidth().height(14.dp),colors=SliderDefaults.colors(thumbColor=Green,activeTrackColor=Green))
                Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),horizontalArrangement=Arrangement.SpaceBetween) { Text(time(position),color=secondary,fontSize=10.sp); Text(time(duration),color=secondary,fontSize=10.sp) }
            }
            NavigationBar(containerColor=surface,contentColor=fg) {
                listOf(Triple("Inicio",Icons.Default.Home,"Inicio"),Triple("Buscar",Icons.Default.Search,"Buscar"),Triple("Biblioteca",Icons.Default.LibraryMusic,"Biblioteca"),Triple("Ajustes",Icons.Default.Settings,"Ajustes")).forEach { (label,icon,target) ->
                    NavigationBarItem(selected=page==target || (target=="Biblioteca"&&(page=="Favoritos"||page.startsWith("playlist:"))),onClick={page=target;search=""},icon={Icon(icon,null)},label={Text(label,fontSize=10.sp)},colors=NavigationBarItemDefaults.colors(selectedIconColor=Green,indicatorColor=Green.copy(alpha=.14f),selectedTextColor=Green,unselectedTextColor=secondary))
                }
            }
        }
    }
    if (toast.isNotBlank()) AlertDialog(onDismissRequest={toast=""},text={Text(toast)},confirmButton={TextButton(onClick={toast=""}){Text("OK")}})
    if (createDialog) PlaylistDialog("Crear playlist","","","",{createDialog=false}) { n,d,c -> val p=Playlist(System.currentTimeMillis().toString(),n,d,c,addSong?.let{listOf(it.uri)}?: emptyList());saveLists(playlists+p);addSong=null;createDialog=false;page="playlist:"+p.id }
    editTarget?.let { p -> PlaylistDialog("Editar playlist",p.name,p.description,p.cover,{editTarget=null}) { n,d,c -> saveLists(playlists.map{if(it.id==p.id)it.copy(name=n,description=d,cover=c)else it});editTarget=null } }
    menuSong?.let { s -> AlertDialog(onDismissRequest={menuSong=null},title={Text(s.title)},text={Column {
        Text(s.artist,color=Gray)
        TextButton(onClick={favorite(s);menuSong=null}){Icon(Icons.Default.Favorite,null);Text(if(s.uri in favorites)"Quitar de favoritos" else "Añadir a favoritos")}
        TextButton(onClick={queue(s);menuSong=null}){Icon(Icons.Default.PlaylistAdd,null);Text("Añadir a la fila")}
        TextButton(onClick={showQueue=true;menuSong=null}){Icon(Icons.Default.QueueMusic,null);Text("Ir a la fila")}
        TextButton(onClick={addSong=s;createDialog=true;menuSong=null}){Icon(Icons.Default.Add,null);Text("Crear playlist con esta canción")}
        playlists.forEach { p -> TextButton(onClick={saveLists(playlists.map{if(it.id==p.id&&s.uri !in it.songs)it.copy(songs=it.songs+s.uri)else it});menuSong=null;toast="Añadida a ${p.name}"}){Text("Añadir a ${p.name}")} }
        TextButton(onClick={showTimer=true;menuSong=null}){Icon(Icons.Default.Timer,null);Text("Apagado automático")}
    }},confirmButton={TextButton(onClick={menuSong=null}){Text("Cerrar")}}) }
    if (showQueue) AlertDialog(onDismissRequest={showQueue=false},title={Text("Fila de reproducción")},text={val p=controller; if(p==null||p.mediaItemCount==0)Text("La fila está vacía.") else LazyColumn(Modifier.heightIn(max=420.dp)){items((0 until p.mediaItemCount).toList()){i->val mi=p.getMediaItemAt(i);val s=songs.firstOrNull{it.uri==mi.localConfiguration?.uri?.toString()};Row(Modifier.fillMaxWidth().clickable{p.seekTo(i,0);p.play();showQueue=false}.padding(8.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(s?.title?:mi.mediaMetadata.title?.toString().orEmpty(),color=fg);Text(s?.artist?:mi.mediaMetadata.artist?.toString().orEmpty(),color=secondary,fontSize=12.sp)};IconButton(onClick={p.removeMediaItem(i)}){Icon(Icons.Default.Close,null)}}}}},confirmButton={TextButton(onClick={showQueue=false}){Text("Listo")}},dismissButton={TextButton(onClick={controller?.clearMediaItems();showQueue=false}){Text("Vaciar fila")}})
    if (showTimer) AlertDialog(onDismissRequest={showTimer=false},title={Text("Apagado automático")},text={Column{Text("Detener después de $timerMins minutos");Slider(value=timerMins.toFloat(),onValueChange={timerMins=it.toInt()},valueRange=5f..180f,steps=34)}},confirmButton={TextButton(onClick={deadline=System.currentTimeMillis()+timerMins*60000L;showTimer=false;toast="Temporizador activado."}){Text("Activar")}},dismissButton={TextButton(onClick={deadline=0;showTimer=false}){Text("Cancelar")}})
    if (eqDialog) AlertDialog(onDismissRequest={eqDialog=false},title={Text("Ecualizador")},text={Column{Text("Graves");Slider(value=bass,onValueChange={bass=it;prefs.edit().putInt("bass",it.toInt()).apply()},valueRange=-10f..10f);Text("Agudos");Slider(value=treble,onValueChange={treble=it;prefs.edit().putInt("treble",it.toInt()).apply()},valueRange=-10f..10f);Text("El procesamiento avanzado depende de las capacidades de audio del dispositivo.",fontSize=12.sp,color=Gray)}},confirmButton={TextButton(onClick={eqDialog=false}){Text("Listo")}})
    if (outputDialog) AlertDialog(onDismissRequest={outputDialog=false},title={Text("Salidas de audio detectadas")},text={Column{if(outputNames.isEmpty())Text("No se detectaron salidas disponibles.") else outputNames.distinct().forEach{Text("• $it",Modifier.padding(vertical=3.dp))};Text("Para cambiar de salida, utiliza también el selector de audio de Android.",fontSize=12.sp,color=Gray)}},confirmButton={TextButton(onClick={outputDialog=false}){Text("Cerrar")}})
    if (clearDataDialog) AlertDialog(onDismissRequest={clearDataDialog=false},title={Text("Limpiar almacenamiento")},text={Text("Se borrarán playlists, favoritos y preferencias. Las canciones originales del teléfono no se eliminarán.")},confirmButton={TextButton(onClick={prefs.edit().clear().apply();saveLists(emptyList());saveFavorites(emptySet());theme="dark";crossfade=0f;mono=false;normalize=false;volume="Normal";eqOn=false;bass=0f;treble=0f;clearDataDialog=false;toast="Datos de la app limpiados."}){Text("Limpiar datos")}},dismissButton={TextButton(onClick={clearDataDialog=false}){Text("Cancelar")}})
}

private fun readPlaylists(prefs: android.content.SharedPreferences): List<Playlist> = try {
    val a=JSONArray(prefs.getString("playlists","[]")?: "[]")
    (0 until a.length()).map { i -> val o=a.getJSONObject(i);val ss=o.optJSONArray("songs")?:JSONArray();Playlist(o.optString("id"),o.optString("name"),o.optString("description"),o.optString("cover"),(0 until ss.length()).map{ss.getString(it)}) }
} catch (_:Exception){emptyList()}

@Composable private fun SongRows(songs:List<Song>,favorites:Set<String>,fg:Color,secondary:Color,current:String?,play:(Song)->Unit,menu:(Song)->Unit,favorite:(Song)->Unit) {
    if(songs.isEmpty()) Box(Modifier.fillMaxWidth().padding(28.dp),contentAlignment=Alignment.Center){Text("Todavía no hay canciones aquí.",color=secondary)}
    else LazyColumn(Modifier.fillMaxWidth(),contentPadding=PaddingValues(bottom=12.dp)){items(songs,key={it.uri}){s->Row(Modifier.fillMaxWidth().clickable{play(s)}.padding(horizontal=16.dp,vertical=7.dp),verticalAlignment=Alignment.CenterVertically){
        Box(Modifier.size(50.dp).clip(RoundedCornerShape(8.dp)).background(Brush.linearGradient(listOf(Color(0xFF245A3C),Color(0xFF252525)))),contentAlignment=Alignment.Center){Icon(Icons.Default.Album,null,tint=Green,modifier=Modifier.size(28.dp))}
        Spacer(Modifier.width(11.dp));Column(Modifier.weight(1f)){Text(s.title,color=if(s.uri==current)Green else fg,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis);Text("${s.artist} · ${time(s.duration)}",color=secondary,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis)}
        if(s.uri in favorites)Icon(Icons.Default.Favorite,null,tint=Green,modifier=Modifier.size(16.dp))
        IconButton(onClick={menu(s)}){Icon(Icons.Default.MoreVert,"Más opciones",tint=secondary)}
    }}}
}
@Composable private fun HomeTile(title:String,sub:String,icon:androidx.compose.ui.graphics.vector.ImageVector,modifier:Modifier,onClick:()->Unit){
    Card(modifier.clickable(onClick=onClick),colors=CardDefaults.cardColors(containerColor=Panel),shape=RoundedCornerShape(16.dp)){Column(Modifier.padding(15.dp)){Icon(icon,null,tint=Green,modifier=Modifier.size(28.dp));Spacer(Modifier.height(14.dp));Text(title,color=Color.White,fontWeight=FontWeight.Bold);Text(sub,color=Gray,fontSize=11.sp)}}
}
@Composable private fun HomeRow(title:String,sub:String,icon:androidx.compose.ui.graphics.vector.ImageVector,fg:Color,secondary:Color,onClick:()->Unit){
    Row(Modifier.fillMaxWidth().clickable(onClick=onClick).padding(horizontal=20.dp,vertical=9.dp),verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF234A34)),contentAlignment=Alignment.Center){Icon(icon,null,tint=Green)};Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text(title,color=fg,fontWeight=FontWeight.SemiBold);Text(sub,color=secondary,fontSize=12.sp)};Icon(Icons.Default.ChevronRight,null,tint=secondary)}
}
@Composable private fun PlaylistDialog(title:String,initialName:String,initialDesc:String,initialCover:String,onDismiss:()->Unit,onSave:(String,String,String)->Unit){
    var name by remember{mutableStateOf(initialName)};var desc by remember{mutableStateOf(initialDesc)};var cover by remember{mutableStateOf(initialCover)}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){it?.let{uri->cover=uri.toString()}}
    AlertDialog(onDismissRequest=onDismiss,title={Text(title)},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){OutlinedTextField(name,{name=it},label={Text("Nombre")},singleLine=true);OutlinedTextField(desc,{desc=it},label={Text("Descripción")},minLines=2);OutlinedButton(onClick={picker.launch("image/*")},modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.Image,null);Spacer(Modifier.width(8.dp));Text(if(cover.isBlank())"Elegir portada" else "Cambiar portada")};if(cover.isNotBlank())Text("Portada seleccionada",color=Green,fontSize=12.sp)}},confirmButton={TextButton(enabled=name.isNotBlank(),onClick={onSave(name.trim(),desc.trim(),cover)}){Text("Guardar")}},dismissButton={TextButton(onClick=onDismiss){Text("Cancelar")}})
}

@Composable private fun SettingsPage(theme:String,onTheme:(String)->Unit,crossfade:Float,onCrossfade:(Float)->Unit,mono:Boolean,onMono:(Boolean)->Unit,normalize:Boolean,onNormalize:(Boolean)->Unit,volume:String,onVolume:(String)->Unit,eq:Boolean,onEq:(Boolean)->Unit,songCount:Int,songBytes:Long,appBytes:Long,cacheBytes:Long,onCache:()->Unit,onClearData:()->Unit){
    val ctx=LocalContext.current;val fg=if(theme=="light")Color(0xFF151515)else Color.White;val sec=if(theme=="light")Color.DarkGray else Gray
    LazyColumn(contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{Text("Configuración",color=fg,fontSize=28.sp,fontWeight=FontWeight.ExtraBold)}
        item{Card(colors=CardDefaults.cardColors(containerColor=if(theme=="light")Color.White else Panel)){Column(Modifier.padding(16.dp)){Text("Apariencia",color=Green,fontWeight=FontWeight.Bold);Text("Tema",color=fg);Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){listOf("Sistema" to "system","Oscuro" to "dark","Claro" to "light").forEach{(label,key)->FilterChip(theme==key,{onTheme(key)},label={Text(label)})}}}}
        }
        item{Card(colors=CardDefaults.cardColors(containerColor=if(theme=="light")Color.White else Panel)){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
            Text("Reproducción",color=Green,fontWeight=FontWeight.Bold);Text("Crossfade: ${crossfade.toInt()} s",color=fg);Slider(value=crossfade,onValueChange=onCrossfade,valueRange=0f..12f,steps=11);Text("El ajuste se guarda; la mezcla cruzada real se integrará en una siguiente versión del motor.",color=sec,fontSize=12.sp)
            Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("Audio mono",color=fg);Text("La aplicación recuerda la preferencia; el procesamiento mono depende del dispositivo.",color=sec,fontSize=11.sp)};Switch(mono,onMono)}
            Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("Normalización de volumen",color=fg);Text("Ajuste inicial de nivel de salida.",color=sec,fontSize=11.sp)};Switch(normalize,onNormalize)}
            Text("Nivel de volumen",color=fg);Row(horizontalArrangement=Arrangement.spacedBy(5.dp)){listOf("Bajo","Normal","Alto").forEach{FilterChip(volume==it,{onVolume(it)},label={Text(it)})}}
            Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("Ecualizador",color=fg);Text("Graves y agudos disponibles en el panel.",color=sec,fontSize=11.sp)};Switch(eq,onEq)}
        }}}
        item{Card(colors=CardDefaults.cardColors(containerColor=if(theme=="light")Color.White else Panel)){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text("Almacenamiento",color=Green,fontWeight=FontWeight.Bold);StorageRow("Canciones indexadas","$songCount",fg,sec);StorageRow("Tamaño de canciones",bytes(songBytes),fg,sec);StorageRow("Datos internos de la app",bytes(appBytes),fg,sec);StorageRow("Caché",bytes(cacheBytes),fg,sec);OutlinedButton(onClick=onCache,modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.DeleteSweep,null);Text("Limpiar caché")};OutlinedButton(onClick=onClearData,modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.DeleteForever,null);Text("Limpiar almacenamiento de la app")};Text("No se eliminan las canciones originales del teléfono.",color=sec,fontSize=11.sp)}}}
        item{Text("NegativeMusic · 0.1.0",color=sec,fontSize=12.sp)}
    }
}
@Composable private fun StorageRow(label:String,value:String,fg:Color,sec:Color){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(label,color=sec,fontSize=13.sp);Text(value,color=fg,fontWeight=FontWeight.SemiBold,fontSize=13.sp)}}
