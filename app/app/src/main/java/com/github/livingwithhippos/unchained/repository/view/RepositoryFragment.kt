package com.github.livingwithhippos.unchained.repository.view

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.activityViewModels
import com.github.livingwithhippos.unchained.R
import com.github.livingwithhippos.unchained.base.UnchainedFragment
import com.github.livingwithhippos.unchained.data.model.PluginVersion
import com.github.livingwithhippos.unchained.data.model.RepositoryInfo
import com.github.livingwithhippos.unchained.data.model.RepositoryPlugin
import com.github.livingwithhippos.unchained.data.repository.InstallResult
import com.github.livingwithhippos.unchained.data.repository.LocalPlugins
import com.github.livingwithhippos.unchained.databinding.FragmentRepositoryBinding
import com.github.livingwithhippos.unchained.plugins.model.Plugin
import com.github.livingwithhippos.unchained.plugins.model.isCompatible
import com.github.livingwithhippos.unchained.repository.model.PluginListener
import com.github.livingwithhippos.unchained.repository.model.PluginRepositoryAdapter
import com.github.livingwithhippos.unchained.repository.model.PluginStatus
import com.github.livingwithhippos.unchained.repository.model.RepositoryListItem
import com.github.livingwithhippos.unchained.repository.viewmodel.PluginRepositoryEvent
import com.github.livingwithhippos.unchained.repository.viewmodel.RepositoryViewModel
import com.github.livingwithhippos.unchained.utilities.EventObserver
import com.github.livingwithhippos.unchained.utilities.extension.showToast
import com.github.livingwithhippos.unchained.utilities.getRepositoryString
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber

@AndroidEntryPoint
class RepositoryFragment : UnchainedFragment(), PluginListener {

    private val viewModel: RepositoryViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val binding = FragmentRepositoryBinding.inflate(inflater, container, false)

        val adapter = PluginRepositoryAdapter(this)
        binding.rvPluginsList.adapter = adapter

        viewModel.pluginsRepositoryLiveData.observe(
            viewLifecycleOwner,
            EventObserver {
                when (it) {
                    PluginRepositoryEvent.Updated -> {
                        // load data from the database
                        viewModel.retrieveDatabaseRepositories(requireContext())
                    }
                    is PluginRepositoryEvent.FullData -> {
                        // data loaded from db, load into UI
                        updateList(adapter, it.dbData, it.installedData)
                    }
                    is PluginRepositoryEvent.Installation -> {
                        when (it.result) {
                            is InstallResult.Error -> {
                                context?.showToast(R.string.plugin_install_not_installed)
                            }
                            InstallResult.Incompatible -> {
                                context?.showToast(R.string.plugin_install_incompatible)
                            }
                            InstallResult.Installed -> {
                                context?.showToast(R.string.plugin_install_installed)
                            }
                        }
                    }
                }
            }
        )

        viewModel.checkCurrentRepositories()

        // observe the search bar for changes
        binding.tiSearch.addTextChangedListener {
            viewModel.filterList(requireContext(), it?.toString())
        }

        return binding.root
    }

    /**
     * Update the plugins list comparing between the latest online repositories data and the currently installed plugins
     *
     * @param adapter the list adapter to submit updates
     * @param data the online repositories data
     * @param installedData the locally installed plugins data
     */
    private fun updateList(
        adapter: PluginRepositoryAdapter,
        data: Map<RepositoryInfo, Map<RepositoryPlugin, List<PluginVersion>>>,
        installedData: LocalPlugins
    ) {
        // todo: accept only https links when adding repositories
        val plugins = mutableListOf<RepositoryListItem>()
        for (repository in data) {
            // add the repository item
            plugins.add(
                RepositoryListItem.Repository(
                    link = repository.key.link,
                    name = repository.key.name,
                    version = repository.key.version,
                    description = repository.key.description,
                    author = repository.key.author,
                )
            )
            val hashedRepoName = getRepositoryString(repository.key.name)
            // no installed plugins from this repo
            if (installedData.pluginsData[hashedRepoName] == null) {
                plugins.addAll(repository.value.map {plug ->
                    val pickedVersion: PluginVersion?
                    // check online plugin compatible versions
                    val latestVersion: PluginVersion? = plug.value.maxByOrNull { it.version }
                    if (latestVersion == null) {
                        Timber.w("BAD PACKAGER! DO NOT RELEASE PLUGINS WITHOUT VERSIONS!  Info: ${plug.key}")
                        pickedVersion = PluginVersion(
                            repository = repository.key.link,
                            plugin = plug.key.name,
                            version = 0F,
                            engine = 0.0,
                            link = repository.key.link,
                        )
                    } else {
                        val latestCompatibleVersion: PluginVersion? = plug.value.filter{ isCompatible(it.engine) }.maxByOrNull { it.version }
                        pickedVersion = latestCompatibleVersion ?: latestVersion
                    }

                    val pickedStatus = when {
                        isCompatible(pickedVersion.engine) -> PluginStatus.new
                        pickedVersion.engine == 0.0 -> PluginStatus.unknown
                        else -> PluginStatus.incompatible
                    }

                    getPluginItemFromVersion(pickedVersion, pickedStatus)
                })
            } else {
                // check against installed plugins from this repo
                plugins.addAll(repository.value.map { onlinePlugin ->
                    // latest available version
                    val latestVersion: PluginVersion? = onlinePlugin.value.maxByOrNull { it.version }
                    // latest available compatible version
                    val latestCompatibleVersion: PluginVersion? = onlinePlugin.value.filter{ isCompatible(it.engine) }.maxByOrNull { it.version }
                    // installed version of the online plugin
                    val installedPlugin: Plugin? = installedData.pluginsData[hashedRepoName]?.firstOrNull { it.name == onlinePlugin.key.name }
                    // check if this plugin has available versions (it should)
                    if (latestVersion == null) {
                        Timber.w("BAD PACKAGER! DO NOT RELEASE PLUGINS WITHOUT VERSIONS!  Info: ${onlinePlugin.key}")
                        val pickedVersion: PluginVersion = if (installedPlugin == null) {
                            PluginVersion(
                                repository = repository.key.link,
                                plugin = onlinePlugin.key.name,
                                version = 0F,
                                engine = 0.0,
                                link = repository.key.link,
                            )
                        } else {
                            Timber.w("BAD PACKAGER! DO NOT REMOVE VERSIONS, CREATE A NEW VERSION INSTEAD!  Info: ${onlinePlugin.key}")
                            PluginVersion(
                                repository = repository.key.link,
                                plugin = onlinePlugin.key.name,
                                version = installedPlugin.version,
                                engine = 0.0,
                                link = repository.key.link,
                            )
                        }

                        val pickedStatus = when {
                            isCompatible(pickedVersion.engine) -> PluginStatus.new
                            pickedVersion.engine == 0.0 -> PluginStatus.unknown
                            else -> PluginStatus.incompatible
                        }
                        getPluginItemFromVersion(pickedVersion, pickedStatus)
                    } else {
                        // at least a version from the repo is available, check compatibility and install status

                        // plugin not installed
                        if (installedPlugin == null) {
                            // no compatible versions
                            if (latestCompatibleVersion == null) {
                                getPluginItemFromVersion(latestVersion, PluginStatus.incompatible)
                            } else {
                                // latest compatible version
                                getPluginItemFromVersion(latestCompatibleVersion, PluginStatus.new)
                            }
                        } else {
                            // plugin installed
                            if (latestCompatibleVersion == null) {
                                Timber.w("BAD PACKAGER! DO NOT REMOVE VERSIONS, CREATE A NEW VERSION INSTEAD!  Info: ${onlinePlugin.key}, installed version is ${installedPlugin.version}")
                                getPluginItemFromVersion(latestVersion, PluginStatus.hasIncompatibleUpdate)
                            } else {
                                if (latestCompatibleVersion.version > installedPlugin.version) {
                                    getPluginItemFromVersion(latestCompatibleVersion, PluginStatus.hasUpdate)
                                } else {
                                    getPluginItemFromVersion(latestCompatibleVersion, PluginStatus.updated)
                                }
                            }
                        }
                    }
                })
            }

            adapter.submitList(plugins)
            adapter.notifyDataSetChanged()
        }
    }

    private fun getPluginItemFromVersion(pluginVersion: PluginVersion, pluginStatus: String): RepositoryListItem.Plugin {
        return RepositoryListItem.Plugin(
            repository = pluginVersion.repository,
            name = pluginVersion.plugin,
            version = pluginVersion.version,
            link = pluginVersion.link,
            status = pluginStatus,
            statusTranslation = getStatusTranslation(pluginStatus)
        )
    }

    private fun getStatusTranslation(status: String): String {
        return when (status) {
            PluginStatus.updated -> getString(R.string.updated)
            PluginStatus.hasUpdate -> getString(R.string.new_update)
            PluginStatus.new -> getString(R.string.new_word)
            PluginStatus.hasIncompatibleUpdate -> getString(R.string.incompatible_update)
            PluginStatus.incompatible -> getString(R.string.incompatible)
            PluginStatus.unknown -> getString(R.string.unknown_status)
            else -> getString(R.string.unknown_status)
        }
    }

    override fun onPluginDownloadClick(plugin: RepositoryListItem.Plugin) {
        Timber.d("Pressed plugin $plugin")
        when (plugin.status) {
            PluginStatus.hasUpdate -> {
                context?.showToast(R.string.downloading)
                viewModel.downloadPlugin(plugin.link, plugin.repository, requireContext())
            }
            PluginStatus.new -> {
                context?.showToast(R.string.downloading)
                viewModel.downloadPlugin(plugin.link, plugin.repository, requireContext())
            }
        }
    }

    override fun onPluginRemoveClick(plugin: RepositoryListItem.Plugin) {
        TODO("Not yet implemented")
    }

    override fun onRepositoryClick(repository: RepositoryListItem.Repository) {
        Timber.d("Pressed repository $repository")
    }
}