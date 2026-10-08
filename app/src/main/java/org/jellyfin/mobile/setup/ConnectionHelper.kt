package org.jellyfin.mobile.setup

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.R
import org.jellyfin.mobile.ui.state.CheckUrlState
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.discovery.LocalServerDiscovery
import org.jellyfin.sdk.discovery.RecommendedServerInfo
import org.jellyfin.sdk.discovery.RecommendedServerInfoScore
import org.jellyfin.sdk.model.api.ServerDiscoveryInfo
import timber.log.Timber

class ConnectionHelper(
    private val context: Context,
    private val jellyfin: Jellyfin,
) {
    @Suppress("LongMethod")
    suspend fun checkServerUrl(enteredUrl: String): CheckUrlState {
        Timber.i("checkServerUrlAndConnection $enteredUrl")

        val candidates = withContext(Dispatchers.IO) {
            jellyfin.discovery.getAddressCandidates(enteredUrl)
        }
        Timber.i("Address candidates are $candidates")

        // Find servers and classify them into groups.
        // BAD servers are collected in case we need an error message,
        // GOOD are kept if there's no GREAT one.
        val badServers = mutableListOf<RecommendedServerInfo>()
        val goodServers = mutableListOf<RecommendedServerInfo>()
        val okServers = mutableListOf<RecommendedServerInfo>()
        val greatServer = withContext(Dispatchers.IO) {
            jellyfin.discovery.getRecommendedServers(candidates)
        }.firstOrNull { recommendedServer ->
            when (recommendedServer.score) {
                RecommendedServerInfoScore.GREAT -> true
                RecommendedServerInfoScore.GOOD -> {
                    goodServers += recommendedServer
                    false
                }
                RecommendedServerInfoScore.OK -> {
                    okServers += recommendedServer
                    false
                }
                RecommendedServerInfoScore.BAD,
                -> {
                    badServers += recommendedServer
                    false
                }
            }
        }

        // 宽容模式：GREAT > GOOD > OK(版本旧但能连) > BAD 里能拿到 systemInfo 的
        // 这样飞牛NAS等魔改/旧版 Jellyfin 也能连上，不被版本检查挡住
        val server = greatServer
            ?: goodServers.firstOrNull()
            ?: okServers.firstOrNull()
            ?: badServers.firstOrNull { it.systemInfo.getOrNull() != null }

        if (server != null) {
            val systemInfo = requireNotNull(server.systemInfo)
            val info = systemInfo.getOrNull()
            val serverVersion = info?.version
            val productName = info?.productName
            Timber.i("Found valid server at ${server.address} with rating ${server.score} and version $serverVersion (productName=$productName)")
            return CheckUrlState.Success(server.address)
        }

        // No valid server found, log and show error message
        val loggedServers = badServers.joinToString { "${it.address}/${it.systemInfo}" }
        Timber.i("No valid servers found, invalid candidates were: $loggedServers")

        val error = when {
            badServers.isNotEmpty() -> {
                val count = badServers.size
                val (unreachableServers, incompatibleServers) = badServers.partition { result -> result.systemInfo.getOrNull() == null }

                StringBuilder().apply {
                    append(context.resources.getQuantityString(R.plurals.connection_error_prefix, count, count))
                    if (unreachableServers.isNotEmpty()) {
                        append("\n\n")
                        append(context.getString(R.string.connection_error_unable_to_reach_sever))
                        append(":\n")
                        append(
                            unreachableServers.joinToString(separator = "\n") { result ->
                                "\u00b7 ${result.address}"
                            },
                        )
                    }
                    if (incompatibleServers.isNotEmpty()) {
                        append("\n\n")
                        append(context.getString(R.string.connection_error_unsupported_version_or_product))
                        append(":\n")
                        append(
                            incompatibleServers.joinToString(separator = "\n") { result ->
                                "\u00b7 ${result.address}"
                            },
                        )
                    }
                }.toString()
            }
            else -> null
        }

        return CheckUrlState.Error(error)
    }

    fun discoverServersAsFlow(): Flow<ServerDiscoveryInfo> =
        jellyfin.discovery
            .discoverLocalServers(maxServers = LocalServerDiscovery.DISCOVERY_MAX_SERVERS)
            .flowOn(Dispatchers.IO)
}
