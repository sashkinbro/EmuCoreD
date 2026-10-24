package com.sbro.emucored.ui.cheats

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sbro.emucored.R
import com.sbro.emucored.data.*
import com.sbro.emucored.data.cheats.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Matched recommendations and an explicit, paged manual catalog are both reachable. */
@Composable
internal fun TypedCheatOnlineCatalogSection(game:GameItem,identity:SelectedGameIdentity?,resolving:Boolean,onInstalled:()->Unit) {
    val context=LocalContext.current;val links=LocalUriHandler.current;val scope=rememberCoroutineScope()
    val repository=remember(context){TypedCheatCatalogRepository(context)}
    val cheats=remember(context){CheatRepository(context)}
    var packs by remember(game.platform){mutableStateOf(emptyList<TypedCheatPack>())}
    var loading by remember(game.platform){mutableStateOf(true)}
    var failed by remember(game.platform){mutableStateOf(false)}
    var browse by remember(game.platform){mutableStateOf(false)}
    var query by remember(game.platform){mutableStateOf("")}
    var visibleCount by remember(game.platform,query,browse){mutableIntStateOf(30)}
    var installing by remember{mutableStateOf<String?>(null)}
    var refresh by remember{mutableIntStateOf(0)}
    val success=stringResource(R.string.cheat_catalog_install_success)
    val failure=stringResource(R.string.cheat_catalog_install_failed)
    LaunchedEffect(game.platform,refresh) {
        loading=true
        val result=withContext(Dispatchers.IO){repository.load(game.platform,refresh>0)}
        packs=result.entries;failed=result.entries.isEmpty() && result.error!=null;loading=false
    }
    val identified=identity?.typed
    val matches=remember(packs,identified){if(identified==null)emptyList() else packs.filter{it.matches(identified)}}
    val visible=remember(packs,matches,browse,query){
        (if(browse)packs else matches).filter{query.isBlank() || it.title.contains(query.trim(),true)}
    }
    Surface(modifier=Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.cheat_catalog_title),style=MaterialTheme.typography.titleLarge)
            if(loading || resolving)CircularProgressIndicator()
            if(failed) {
                Text(stringResource(R.string.content_catalog_failed),color=MaterialTheme.colorScheme.error)
                TextButton(onClick={refresh++}){Text(stringResource(R.string.tv_storage_picker_install_retry))}
            }
            OutlinedButton(onClick={browse=!browse},enabled=!loading){Text(stringResource(if(browse)
                R.string.content_compatible_game_version_section else R.string.cheat_catalog_browse_all))}
            if(browse) {
                Text(stringResource(R.string.cheat_catalog_manual_warning),style=MaterialTheme.typography.bodySmall)
                OutlinedTextField(value=query,onValueChange={query=it},singleLine=true,
                    label={Text(stringResource(R.string.cheat_manager_search))},modifier=Modifier.fillMaxWidth())
            }
            if(!loading && !resolving && visible.isEmpty())Text(stringResource(R.string.cheat_manager_search_empty))
            visible.take(visibleCount).forEach { pack ->
                val matching=identified?.let{pack.matches(it)}==true
                Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text(pack.title,style=MaterialTheme.typography.titleSmall)
                        Text(pack.formats.joinToString(", "),style=MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick={runCatching{links.openUri(pack.sourceUrl)}}){Text(stringResource(R.string.content_source))}
                            Button(enabled=installing==null && !resolving,onClick={
                                installing=pack.id
                                scope.launch {
                                    val result=withContext(Dispatchers.IO){runCatching {
                                        val raw=repository.download(pack)
                                        cheats.importTypedPack(game.path,identified?:CheatGameIdentity(game.platform),pack,raw,manual=!matching)>0
                                    }.getOrDefault(false)}
                                    installing=null
                                    if(result){CheatRuntimeUpdates.notifyChanged(game.path);onInstalled()}
                                    Toast.makeText(context,if(result)success else failure,Toast.LENGTH_LONG).show()
                                }
                            }) {if(installing==pack.id)CircularProgressIndicator(Modifier.size(16.dp),strokeWidth=2.dp)
                                else Text(stringResource(if(matching)R.string.cheat_manager_import_pnach else R.string.cheat_catalog_manual_attach))}
                        }
                    }
                }
            }
            if(visible.size>visibleCount)TextButton(onClick={visibleCount+=30}){Text(stringResource(R.string.detail_show_more))}
        }
    }
}
