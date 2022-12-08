package com.github.livingwithhippos.unchained.data.repository

import android.content.Context
import android.net.Uri
import com.github.livingwithhippos.unchained.data.local.AssetsManager
import com.github.livingwithhippos.unchained.plugins.model.Plugin
import com.github.livingwithhippos.unchained.utilities.getPluginFilename
import com.github.livingwithhippos.unchained.utilities.getRepositoryString
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import javax.inject.Inject

class PluginRepository {

    // todo: remove all context and use new function assetsManager.getAssetFileInputStream(fileName)

    // todo: inject
    private val pluginAdapter: JsonAdapter<Plugin> = Moshi.Builder()
        .build()
        .adapter(Plugin::class.java)


    suspend fun getPluginsNew(context: Context): Pair<List<Plugin>, Int> =
        withContext(Dispatchers.IO) {

            /**
             * get local .json and .unchained files from the search_plugin folder in the assets folder
             */
            val pluginFiles = mutableListOf<File>()
            val pluginFolder = context.getDir("plugins", Context.MODE_PRIVATE)
            if (pluginFolder.exists()) {
                pluginFolder.walk().forEach {
                    if (it.isFile && it.name.endsWith(TYPE_UNCHAINED, ignoreCase = true)) {
                        pluginFiles.add(it)
                    }
                }
            }

            val plugins = mutableListOf<Plugin>()

            var errors = 0

            for (file in pluginFiles) {
                try {
                    val json = file.readText()
                    val plugin: Plugin? = pluginAdapter.fromJson(json)
                    if (plugin != null)
                        plugins.add(plugin)
                    else
                        errors++
                } catch (ex: Exception) {
                    Timber.e("Exception while parsing json plugin: $ex")
                }
            }

            Pair(plugins, errors)
        }

    fun removePlugin(context: Context, repository: String, plugin: String) {
        val pluginFolder = context.getDir("plugins", Context.MODE_PRIVATE)
        val repoName = getRepositoryString(repository)
        val filename = getPluginFilename(plugin)

        if (!pluginFolder.exists()) {
            Timber.e("Plugin folder not found")
            return
        }

        val repoFolder = File(pluginFolder, repoName)
        if (!repoFolder.exists()) {
            Timber.e("Plugin repository folder not found: $repoName")
            return
        }

        val file = File(repoFolder, filename)
        if (!file.exists()) {
            Timber.e("Plugin file not found: $filename")
            return
        }
        try {
            file.delete()
        } catch (ex: IOException) {
            Timber.e("Plugin file not deleted: $ex")
        }
    }

    private fun getPluginFromJSON(json: String): Plugin? {
        return try {
            pluginAdapter.fromJson(json)
        } catch (ex: Exception) {
            Timber.e("Error reading json string, exception ${ex.message}")
            null
        }
    }

    fun getExternalPlugin(context: Context, filename: String): Plugin? {
        val file = File(context.filesDir, filename)
        return getPluginFromJSON(file.readText())
    }

    fun removeExternalPlugins(context: Context): Int {
        return try {
            val plugins = context.filesDir.listFiles { _, name ->
                name.endsWith(TYPE_UNCHAINED, ignoreCase = true)
            }

            plugins?.forEach {
                it.delete()
            }

            plugins?.size ?: -1
        } catch (e: SecurityException) {
            Timber.d("Security exception deleting plugins files: ${e.message}")
            -1
        }
    }

    suspend fun addExternalPlugin(
        context: Context,
        data: Uri,
        customFileName: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val filename = customFileName ?: data.path?.replace("%2F", "/")?.split("/")?.last()
        if (filename != null) {
            // save the file locally
            return@withContext try {
                context.contentResolver.openInputStream(data)?.use { inputStream ->
                    val buffer: ByteArray = inputStream.readBytes()
                    context.openFileOutput(filename, Context.MODE_PRIVATE).use {
                        it.write(buffer)
                    }
                }
                true
            } catch (exception: Exception) {
                Timber.e("Error loading the file ${data.path}: ${exception.message}")
                false
            }
        }
        return@withContext true
    }

    suspend fun addExternalPlugin(
        pluginsFolder: File,
        pluginFile: File
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val savedPluginPath = File(pluginsFolder, pluginFile.name)
            // remove file if already existing
            if (savedPluginPath.exists())
                savedPluginPath.delete()
            // copy plugin from cache to internal memory
            pluginFile.inputStream().use { inputStream ->
                savedPluginPath.outputStream().use {
                    inputStream.copyTo(it)
                }
            }
        } catch (exception: Exception) {
            Timber.e("Error saving the plugin ${pluginFile.name}: ${exception.message}")
            return@withContext false
        }
        return@withContext true
    }

    suspend fun addExternalPlugin(context: Context, source: String): Boolean =
        withContext(Dispatchers.IO) {

            val plugin: Plugin? = getPluginFromJSON(source)

            if (plugin != null) {
                val filename = plugin.name + TYPE_UNCHAINED
                try {
                    context.openFileOutput(filename, Context.MODE_PRIVATE).use {
                        val buffer: ByteArray = source.toByteArray()
                        it.write(buffer)
                    }
                    return@withContext true
                } catch (exception: IOException) {
                    Timber.e("Error adding the plugin $filename: ${exception.message}")
                    false
                }
            } else
                return@withContext false
        }

    suspend fun readPassedPlugin(context: Context, data: Uri): Plugin? =
        withContext(Dispatchers.IO) {

            val filename = data.path?.split("/")?.last()
            if (filename != null) {
                try {
                    context.contentResolver.openInputStream(data)?.use { inputStream ->
                        val json = inputStream.bufferedReader().readText()

                        return@withContext getPluginFromJSON(json)
                    }
                } catch (exception: Exception) {
                    Timber.e("Error adding the plugin $filename: ${exception.message}")
                }
            }

            null
        }

    suspend fun readPluginFile(pluginFile: File): Plugin? =
        withContext(Dispatchers.IO) {
            try {
                pluginFile.bufferedReader().use {
                    val json = it.readText()
                    return@withContext getPluginFromJSON(json)
                }
            } catch (exception: Exception) {
                Timber.e("Error adding the plugin ${pluginFile.name}: ${exception.message}")
            }
            null
        }

    companion object {
        const val TYPE_UNCHAINED = ".unchained"
    }
}
